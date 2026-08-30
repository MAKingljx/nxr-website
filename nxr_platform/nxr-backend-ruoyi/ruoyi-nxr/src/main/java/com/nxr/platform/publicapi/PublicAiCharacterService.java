package com.nxr.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicAiCharacterService {

    private static final Logger log = LoggerFactory.getLogger(PublicAiCharacterService.class);
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile(
        "<p\\b[^>]*>(.*?)</p>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
        "en", "zh-cn", "ja", "ko", "fr", "de", "es", "it", "pt"
    );
    private static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(
        Map.entry("en", "English"),
        Map.entry("zh-cn", "Simplified Chinese"),
        Map.entry("ja", "Japanese"),
        Map.entry("ko", "Korean"),
        Map.entry("fr", "French"),
        Map.entry("de", "German"),
        Map.entry("es", "Spanish"),
        Map.entry("it", "Italian"),
        Map.entry("pt", "Portuguese")
    );

    private final JdbcClient jdbcClient;
    private final DeepSeekAiClient deepSeekAiClient;
    private final ObjectMapper objectMapper;

    public PublicAiCharacterService(JdbcClient jdbcClient) {
        this(jdbcClient, null, new ObjectMapper());
    }

    @Autowired
    public PublicAiCharacterService(
        JdbcClient jdbcClient,
        DeepSeekAiClient deepSeekAiClient,
        ObjectMapper objectMapper
    ) {
        this.jdbcClient = jdbcClient;
        this.deepSeekAiClient = deepSeekAiClient;
        this.objectMapper = objectMapper;
    }

    public AiCharacterResponse loadCharacterInfo(AiCharacterRequest request) {
        NormalizedAiRequest normalized = normalize(request);
        AiCharacterResponse cached = findCache(normalized);
        if (isReusableCache(cached, normalized)) {
            return cached;
        }
        return generateAndCache(normalized, null);
    }

    public AiCharacterResponse streamCharacterInfo(
        AiCharacterRequest request,
        Consumer<String> chunkConsumer
    ) {
        NormalizedAiRequest normalized = normalize(request);
        AiCharacterResponse cached = findCache(normalized);
        if (isReusableCache(cached, normalized)) {
            return cached;
        }
        return generateAndCache(normalized, chunkConsumer);
    }

    private AiCharacterResponse generateAndCache(
        NormalizedAiRequest request,
        Consumer<String> chunkConsumer
    ) {
        String html;
        String provider;
        if (deepSeekAiClient != null && deepSeekAiClient.isEnabled()) {
            try {
                DeepSeekAiClient.Generation generation = chunkConsumer == null
                    ? deepSeekAiClient.generate(buildMessages(request))
                    : deepSeekAiClient.stream(buildMessages(request), chunkConsumer);
                html = sanitizeModelHtml(generation.content());
                provider = "deepseek";
            } catch (DeepSeekAiClient.AiProviderException exc) {
                log.warn(
                    "DeepSeek character generation failed for {}; using local fallback",
                    request.certId(),
                    exc
                );
                html = buildFallbackHtml(request);
                provider = "local-fallback";
            }
        } else {
            html = buildFallbackHtml(request);
            provider = "local";
        }

        saveCache(request, html, provider);
        return new AiCharacterResponse(
            request.certId(),
            request.brand(),
            request.characterName(),
            request.languageCode(),
            html,
            provider,
            false,
            LocalDateTime.now()
        );
    }

    private AiCharacterResponse findCache(NormalizedAiRequest request) {
        return jdbcClient.sql(
                """
                SELECT cert_id, brand_name, character_name, language_code,
                       content_html, provider_code, updated_at
                FROM ai_character_cache
                WHERE cert_id = :certId
                  AND language_code = :languageCode
                """
            )
            .param("certId", request.certId())
            .param("languageCode", request.languageCode())
            .query((rs, rowNum) -> new AiCharacterResponse(
                rs.getString("cert_id"),
                rs.getString("brand_name"),
                rs.getString("character_name"),
                rs.getString("language_code"),
                rs.getString("content_html"),
                rs.getString("provider_code"),
                true,
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .optional()
            .orElse(null);
    }

    private boolean isReusableCache(
        AiCharacterResponse cached,
        NormalizedAiRequest request
    ) {
        if (cached == null) {
            return false;
        }
        if (
            !clean(cached.brand()).equals(request.brand())
                || !clean(cached.character()).equals(request.characterName())
        ) {
            return false;
        }
        boolean localCache = cached.provider() != null && cached.provider().startsWith("local");
        if (!localCache) {
            return true;
        }
        boolean deepSeekEnabled = deepSeekAiClient != null && deepSeekAiClient.isEnabled();
        return !deepSeekEnabled;
    }

    private void saveCache(NormalizedAiRequest request, String html, String provider) {
        jdbcClient.sql(
                """
                INSERT INTO ai_character_cache (
                    cert_id, brand_name, character_name, language_code,
                    content_html, provider_code
                ) VALUES (
                    :certId, :brandName, :characterName, :languageCode,
                    :contentHtml, :providerCode
                )
                ON DUPLICATE KEY UPDATE
                    brand_name = VALUES(brand_name),
                    character_name = VALUES(character_name),
                    content_html = VALUES(content_html),
                    provider_code = VALUES(provider_code),
                    updated_at = CURRENT_TIMESTAMP
                """
            )
            .param("certId", request.certId())
            .param("brandName", request.brand())
            .param("characterName", request.characterName())
            .param("languageCode", request.languageCode())
            .param("contentHtml", html)
            .param("providerCode", provider)
            .update();
    }

    private NormalizedAiRequest normalize(AiCharacterRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        String certId = clean(request.certId()).toUpperCase(Locale.ROOT);
        String brand = clean(request.brand());
        String character = clean(request.character());
        String language = clean(request.language()).toLowerCase(Locale.ROOT);
        if (certId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "certId is required");
        }
        if (character.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "character is required");
        }
        if (brand.isBlank()) {
            brand = "Unknown";
        }
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            language = "en";
        }
        return new NormalizedAiRequest(certId, brand, character, language);
    }

    private List<Map<String, String>> buildMessages(NormalizedAiRequest request) {
        String languageName = LANGUAGE_NAMES.getOrDefault(request.languageCode(), "English");
        String systemPrompt = "You are a careful collectible card detail-page writer. "
            + "Write all prose in " + languageName + ". Return exactly two short paragraphs "
            + "separated by a blank line. Do not return HTML, headings, lists, JSON, markdown, "
            + "or source labels. In paragraph one, describe the character's appearance, most "
            + "recognizable traits, and core abilities. In paragraph two, describe the character's "
            + "typical role in the anime or narrative and naturally blend reliable setting details "
            + "into the prose. Use only high-confidence facts. If uncertain, omit the detail instead "
            + "of inventing it. Avoid rankings, prices, and market commentary.";
        Map<String, String> context = new LinkedHashMap<>();
        context.put("cert_id", request.certId());
        context.put("brand", request.brand());
        context.put("character", request.characterName());
        context.put("language", request.languageCode());
        final String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("Unable to encode AI card context", exc);
        }
        String userPrompt = "Write the character overview for this verified card detail page. "
            + "The final text must be suitable for direct display inside a modal. Card context: "
            + contextJson;
        return List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        );
    }

    static String sanitizeModelHtml(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        Matcher matcher = PARAGRAPH_PATTERN.matcher(normalized);
        List<String> paragraphs = new ArrayList<>();
        while (matcher.find() && paragraphs.size() < 2) {
            addParagraph(paragraphs, matcher.group(1));
        }
        if (paragraphs.isEmpty()) {
            String text = normalized
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                .replaceAll("<[^>]+>", "");
            for (String part : text.split("\\n\\s*\\n+")) {
                addParagraph(paragraphs, part);
                if (paragraphs.size() == 2) {
                    break;
                }
            }
        }
        if (paragraphs.isEmpty()) {
            throw new DeepSeekAiClient.AiProviderException("Model returned no usable paragraph content");
        }
        return String.join("", paragraphs);
    }

    private static void addParagraph(List<String> paragraphs, String value) {
        String text = clean(value == null ? "" : value.replaceAll("<[^>]+>", ""));
        if (!text.isBlank()) {
            paragraphs.add("<p>" + escapeHtml(text) + "</p>");
        }
    }

    private String buildFallbackHtml(NormalizedAiRequest request) {
        String escapedCharacter = escapeHtml(request.characterName());
        String escapedBrand = escapeHtml(request.brand());
        return "<p><strong>" + escapedCharacter + "</strong> is presented here in the "
            + escapedBrand + " collector context. Use the verified certificate metadata and condition "
            + "record as the authoritative reference.</p>"
            + "<p>Compare official checklists, set details, and population changes before making "
            + "rarity or value assumptions. Additional AI context becomes available when the DeepSeek "
            + "provider is configured.</p>";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    public record AiCharacterRequest(
        @JsonAlias("cert_id") String certId,
        String brand,
        String character,
        String language
    ) {
    }

    public record AiCharacterResponse(
        String certId,
        String brand,
        String character,
        String language,
        String html,
        String provider,
        boolean cached,
        LocalDateTime generatedAt
    ) {
    }

    private record NormalizedAiRequest(
        String certId,
        String brand,
        String characterName,
        String languageCode
    ) {
    }
}
