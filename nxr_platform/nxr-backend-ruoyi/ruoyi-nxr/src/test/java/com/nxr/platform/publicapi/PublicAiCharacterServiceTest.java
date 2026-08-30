package com.nxr.platform.publicapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class PublicAiCharacterServiceTest {

    private PublicAiCharacterService service;
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_public_ai;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute(
            """
            CREATE TABLE ai_character_cache (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                cert_id VARCHAR(64) NOT NULL,
                brand_name VARCHAR(128) NOT NULL,
                character_name VARCHAR(255) NOT NULL,
                language_code VARCHAR(16) NOT NULL,
                content_html TEXT NOT NULL,
                provider_code VARCHAR(32) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (cert_id, language_code)
            )
            """
        );
        jdbcClient = JdbcClient.create(dataSource);
        service = new PublicAiCharacterService(jdbcClient);
    }

    @Test
    void missingProviderUsesSafeLocalFallbackAndCachesIt() {
        PublicAiCharacterService.AiCharacterRequest request =
            new PublicAiCharacterService.AiCharacterRequest(
                "abc123",
                "Pokemon",
                "Pikachu <script>",
                "zh-CN"
            );

        PublicAiCharacterService.AiCharacterResponse first = service.loadCharacterInfo(request);
        PublicAiCharacterService.AiCharacterResponse second = service.loadCharacterInfo(request);

        assertThat(first.provider()).isEqualTo("local");
        assertThat(first.cached()).isFalse();
        assertThat(first.html()).contains("Pikachu &lt;script&gt;").doesNotContain("<script>");
        assertThat(first.language()).isEqualTo("zh-cn");
        assertThat(second.cached()).isTrue();
        assertThat(second.html()).isEqualTo(first.html());
    }

    @Test
    void modelHtmlKeepsTwoEscapedParagraphsOnly() {
        String html = PublicAiCharacterService.sanitizeModelHtml(
            "<p>First & <img src=x onerror=alert(1)> safe</p>"
                + "<p>Second paragraph</p><p>Ignored paragraph</p>"
        );

        assertThat(html)
            .isEqualTo("<p>First &amp; safe</p><p>Second paragraph</p>")
            .doesNotContain("onerror", "Ignored");
    }

    @Test
    void staleProviderCacheIsReplacedWhenCardIdentityChanges() {
        jdbcClient.sql(
                """
                INSERT INTO ai_character_cache (
                    cert_id, brand_name, character_name, language_code,
                    content_html, provider_code
                ) VALUES (
                    'ABC123', 'Old Brand', 'Old Character', 'en',
                    '<p>Stale first.</p><p>Stale second.</p>', 'deepseek'
                )
                """
            )
            .update();

        PublicAiCharacterService.AiCharacterResponse response = service.loadCharacterInfo(
            new PublicAiCharacterService.AiCharacterRequest(
                "abc123",
                "New Brand",
                "New Character",
                "en"
            )
        );

        assertThat(response.cached()).isFalse();
        assertThat(response.provider()).isEqualTo("local");
        assertThat(response.brand()).isEqualTo("New Brand");
        assertThat(response.character()).isEqualTo("New Character");
        assertThat(response.html()).doesNotContain("Stale first");
    }

    @Test
    void emptyModelResponseIsRejected() {
        assertThatThrownBy(() -> PublicAiCharacterService.sanitizeModelHtml("<div></div>"))
            .isInstanceOf(DeepSeekAiClient.AiProviderException.class);
    }
}
