package com.nxr.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicAiCharacterService {

    private final JdbcClient jdbcClient;

    public PublicAiCharacterService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AiCharacterResponse loadCharacterInfo(AiCharacterRequest request) {
        NormalizedAiRequest normalized = normalize(request);
        return jdbcClient.sql(
                """
                SELECT cert_id, brand_name, character_name, language_code, content_html, provider_code, updated_at
                FROM ai_character_cache
                WHERE cert_id = :certId
                  AND language_code = :languageCode
                """
            )
            .param("certId", normalized.certId())
            .param("languageCode", normalized.languageCode())
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
            .orElseGet(() -> createAndCache(normalized));
    }

    private AiCharacterResponse createAndCache(NormalizedAiRequest request) {
        String html = buildHtml(request);
        jdbcClient.sql(
                """
                INSERT INTO ai_character_cache (cert_id, brand_name, character_name, language_code, content_html, provider_code)
                VALUES (:certId, :brandName, :characterName, :languageCode, :contentHtml, 'local')
                """
            )
            .param("certId", request.certId())
            .param("brandName", request.brand())
            .param("characterName", request.characterName())
            .param("languageCode", request.languageCode())
            .param("contentHtml", html)
            .update();

        return new AiCharacterResponse(
            request.certId(),
            request.brand(),
            request.characterName(),
            request.languageCode(),
            html,
            "local",
            false,
            LocalDateTime.now()
        );
    }

    private NormalizedAiRequest normalize(AiCharacterRequest request) {
        String certId = clean(request.certId());
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
        if (language.isBlank()) {
            language = "en";
        }
        return new NormalizedAiRequest(certId, brand, character, language);
    }

    private String buildHtml(NormalizedAiRequest request) {
        String escapedCharacter = escapeHtml(request.characterName());
        String escapedBrand = escapeHtml(request.brand());
        String focus = focusLabel(request.brand());
        return """
            <h3>%s</h3>
            <p><strong>Brand:</strong> %s</p>
            <p>This NXR reference summarizes the %s context for collectors using the verified certificate data. It is generated locally and cached, so repeated requests stay fast and consistent.</p>
            <h3>Collector Context</h3>
            <ul>
              <li>Use the certificate grade and sub-grades as the authoritative condition record.</li>
              <li>Compare release, set, film, team, or group metadata before making value assumptions.</li>
              <li>For rare variants, verify official checklists and population changes over time.</li>
            </ul>
            <h3>Search Hints</h3>
            <p>Useful lookup terms: %s, %s, NXR certificate %s.</p>
            """.formatted(escapedCharacter, escapedBrand, focus, escapedCharacter, escapedBrand, escapeHtml(request.certId()));
    }

    private String focusLabel(String brand) {
        String lowerBrand = brand.toLowerCase(Locale.ROOT);
        if (lowerBrand.contains("movie") || lowerBrand.contains("museum")) {
            return "film or collection";
        }
        if (lowerBrand.contains("sports")) {
            return "sports card";
        }
        return "character or card";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
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
