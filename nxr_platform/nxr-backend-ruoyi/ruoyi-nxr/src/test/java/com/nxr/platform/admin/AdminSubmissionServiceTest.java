package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nxr.platform.shared.CertificateIdPolicy;
import com.nxr.platform.shared.GradeLabelResolver;
import com.nxr.platform.shared.NxrDictionaryService;
import java.math.BigDecimal;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class AdminSubmissionServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AdminSubmissionService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_submission_products;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        resetSchema();
        JdbcClient jdbcClient = JdbcClient.create(jdbcTemplate);
        service = new AdminSubmissionService(
            jdbcClient,
            jdbcTemplate,
            new CertificateIdPolicy(),
            new GradeLabelResolver(),
            new NxrDictionaryService(jdbcClient)
        );
    }

    @Test
    void legacyLabelProductCreatesCanonicalMerchWithoutScore() {
        AdminSubmissionService.SubmissionDetailResponse created = service.createSubmission(
            request("5703018202", "label_product", null, "sports_card", null)
        );

        assertThat(created.productType()).isEqualTo("merch_product");
        assertThat(created.productTypeLabel()).isEqualTo("Merch Product");
        assertThat(created.merchDescription()).isEqualTo("Limited collector merchandise");
        assertThat(created.cardCategory()).isEqualTo("trading_card");
        assertThat(created.finalGradeValue()).isNull();
        assertThat(created.finalGradeLabel()).isNull();
        assertThat(count("grading_submission")).isOne();
        assertThat(count("grading_score")).isZero();
    }

    @Test
    void vintageProductRequiresAnActiveDictionaryValue() {
        assertThatThrownBy(() -> service.createSubmission(
            request("5703018203", "vintage_product", "Archive", "trading_card", null)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(count("grading_submission")).isZero();

        addVintageClassification("Archive");
        AdminSubmissionService.SubmissionDetailResponse created = service.createSubmission(
            request("5703018203", "vintage_product", "archive", "movie_film", null)
        );

        assertThat(created.vintageClassification()).isEqualTo("Archive");
        assertThat(created.cardCategory()).isEqualTo("trading_card");
        assertThat(count("grading_score")).isZero();
    }

    @Test
    void gradedCardRequiresAllScoresAndPersistsTheCalculatedGrade() {
        assertThatThrownBy(() -> service.createSubmission(
            request("5703018204", "graded_card", null, "trading_card", null)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Centering score is required");

        AdminSubmissionService.SubmissionDetailResponse created = service.createSubmission(
            request("5703018204", "graded_card", null, "trading_card", new BigDecimal("9.0"))
        );

        assertThat(created.finalGradeValue()).isEqualByComparingTo("9.0");
        assertThat(created.finalGradeLabel()).isEqualTo("9");
        assertThat(count("grading_score")).isOne();
    }

    @Test
    void acceptsHistoricalPunctuationAliasButRejectsUnknownProductType() {
        AdminSubmissionService.SubmissionDetailResponse created = service.createSubmission(
            request("5703018206", "label-product", null, "trading_card", null)
        );
        assertThat(created.productType()).isEqualTo("merch_product");

        assertThatThrownBy(() -> service.createSubmission(
            request("5703018207", "unsupported_product", null, "trading_card", null)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Unsupported product type");

        assertThat(count("grading_submission")).isOne();
    }

    @Test
    void changingAGradedCardToMerchProductDeletesItsScoreRow() {
        AdminSubmissionService.SubmissionDetailResponse created = service.createSubmission(
            request("5703018205", "graded_card", null, "sports_card", new BigDecimal("9.5"))
        );
        assertThat(count("grading_score")).isOne();

        AdminSubmissionService.SubmissionDetailResponse updated = service.updateSubmission(
            created.id(),
            request("5703018205", "label_product", null, "sports_card", null)
        );

        assertThat(updated.productType()).isEqualTo("merch_product");
        assertThat(updated.merchDescription()).isEqualTo("Limited collector merchandise");
        assertThat(updated.cardCategory()).isEqualTo("trading_card");
        assertThat(updated.centeringScore()).isNull();
        assertThat(count("grading_score")).isZero();
    }

    @Test
    void nonGradedPopulationUsesProductIdentityAndVintageClassification() {
        service.createSubmission(request("5703018210", "label_product", null, "trading_card", null));
        AdminSubmissionService.SubmissionDetailResponse secondLabel = service.createSubmission(
            request("5703018211", "label_product", null, "trading_card", null)
        );
        assertThat(secondLabel.populationValue()).isEqualTo(2);

        addVintageClassification("Archive A");
        addVintageClassification("Archive B");
        service.createSubmission(request("5703018212", "vintage_product", "Archive A", "trading_card", null));
        AdminSubmissionService.SubmissionDetailResponse secondArchiveA = service.createSubmission(
            request("5703018213", "vintage_product", "Archive A", "trading_card", null)
        );
        AdminSubmissionService.SubmissionDetailResponse firstArchiveB = service.createSubmission(
            request("5703018214", "vintage_product", "Archive B", "trading_card", null)
        );

        assertThat(secondArchiveA.populationValue()).isEqualTo(2);
        assertThat(firstArchiveB.populationValue()).isEqualTo(1);
    }

    @Test
    void populationCalculatorSupportsAllProductTypesAndIncompleteVintageDefaultsToOne() {
        AdminSubmissionService.PopulationCalculationResponse graded = service.calculatePopulation(
            populationRequest("graded_card", null, "Mint 9")
        );
        AdminSubmissionService.PopulationCalculationResponse merch = service.calculatePopulation(
            populationRequest("merch_product", null, null)
        );
        AdminSubmissionService.PopulationCalculationResponse incompleteVintage = service.calculatePopulation(
            populationRequest("vintage_product", null, null)
        );

        addVintageClassification("Archive");
        AdminSubmissionService.PopulationCalculationResponse vintage = service.calculatePopulation(
            populationRequest("vintage_product", "archive", null)
        );

        assertThat(graded.populationValue()).isEqualTo(1);
        assertThat(merch.populationValue()).isEqualTo(1);
        assertThat(vintage.populationValue()).isEqualTo(1);
        assertThat(incompleteVintage.populationValue()).isEqualTo(1);
        assertThat(incompleteVintage.calculation()).isEqualTo("Incomplete data for POP calculation");
        assertThat(incompleteVintage.details().existingCount()).isZero();
    }

    @Test
    void historicalLabelRowsParticipateInMerchMatchingAndPopulation() {
        jdbcTemplate.update(
            """
            INSERT INTO grading_submission (
                cert_id, product_type_code, merch_description, card_category_code, card_name,
                year_label, brand_name, set_name, card_number, language_code,
                population_value, status_code, grading_phase_code
            ) VALUES (?, 'label_product', ?, 'trading_card', ?, ?, ?, ?, ?, 'EN', 1, 'approved', 'human_review')
            """,
            "5703018215",
            "Legacy description",
            "Test Card",
            "1999",
            "Pokemon",
            "Base Set",
            "4/102"
        );

        AdminSubmissionService.SubmissionDetailResponse created = service.createSubmission(
            request("5703018216", "merch_product", null, "trading_card", null)
        );
        AdminSubmissionService.MatchCardResponse match = service.matchCard(
            new AdminSubmissionService.MatchCardRequest("merch_product", "trading_card", "Base Set", "4/102")
        );

        assertThat(created.populationValue()).isEqualTo(2);
        assertThat(match.found()).isTrue();
        assertThat(match.cardName()).isEqualTo("Test Card");
        assertThat(match.merchDescription()).isEqualTo("Legacy description");
    }

    @Test
    void pythonTemporaryProjectionHasPriorityAndReturnsMerchDescription() {
        jdbcTemplate.update(
            """
            INSERT INTO nxr_python_match_projection (
                source_code, cert_id, product_type_code, card_category_code,
                set_name, card_number, card_name, brand_name, year_label,
                variety_name, language_code, merch_description, status_code,
                source_updated_at
            ) VALUES
                ('cards', 'MATCH001', 'merch_product', 'trading_card',
                 'Base Set', '4/102', 'Published Name', 'Pokemon', '1999',
                 'Published Variety', 'EN', 'Published description', 'published',
                 TIMESTAMP '2026-08-01 10:00:00'),
                ('temp_cards', 'MATCH002', 'merch_product', 'trading_card',
                 'Base Set', '4/102', 'Temporary Name', 'Pokemon', '2000',
                 'Temporary Variety', 'EN', 'Temporary description', 'approved',
                 TIMESTAMP '2026-08-01 09:00:00')
            """
        );

        AdminSubmissionService.MatchCardResponse match = service.matchCard(
            new AdminSubmissionService.MatchCardRequest(
                "merch_product", "trading_card", "Base Set", "4/102"
            )
        );

        assertThat(match.found()).isTrue();
        assertThat(match.cardName()).isEqualTo("Temporary Name");
        assertThat(match.merchDescription()).isEqualTo("Temporary description");
        assertThat(match.source()).isEqualTo("temp_cards");
    }

    @Test
    void listFiltersMatchPythonAdminFieldsAndReturnEnteredBy() {
        service.createSubmission(
            request("5703018208", "graded_card", null, "sports_card", new BigDecimal("9.0"))
        );
        service.createSubmission(
            request("5703018209", "merch_product", null, "trading_card", null)
        );

        AdminSubmissionService.SubmissionListResponse result = service.listSubmissions(
            new AdminSubmissionService.SubmissionListFilter(
                1,
                25,
                "pending",
                null,
                "5703018208",
                "Test",
                "sports_card",
                "graded_card",
                "poke",
                "9",
                "Base",
                "English",
                "NXR Admin",
                "cert_id",
                "asc"
            )
        );

        assertThat(result.total()).isOne();
        assertThat(result.pageSize()).isEqualTo(25);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.certId()).isEqualTo("5703018208");
            assertThat(item.productType()).isEqualTo("graded_card");
            assertThat(item.enteredBy()).isEqualTo("admin");
            assertThat(item.finalGradeValue()).isEqualByComparingTo("9.0");
        });
    }

    private AdminSubmissionService.MutateSubmissionRequest request(
        String certId,
        String productType,
        String vintageClassification,
        String cardCategory,
        BigDecimal score
    ) {
        return new AdminSubmissionService.MutateSubmissionRequest(
            certId,
            productType,
            vintageClassification,
            productType != null && (productType.contains("label") || productType.contains("merch"))
                ? "Limited collector merchandise"
                : null,
            cardCategory,
            "Test Card",
            null,
            null,
            null,
            null,
            "Basketball",
            null,
            "1999",
            "Pokemon",
            null,
            null,
            "Base Set",
            "4/102",
            "EN",
            1,
            score,
            score,
            score,
            score,
            "test",
            1L
        );
    }

    private AdminSubmissionService.PopulationCalculationRequest populationRequest(
        String productType,
        String vintageClassification,
        String finalGradeLabel
    ) {
        return new AdminSubmissionService.PopulationCalculationRequest(
            productType,
            "trading_card",
            "Test Card",
            "Base Set",
            "4/102",
            "EN",
            null,
            null,
            null,
            null,
            null,
            null,
            vintageClassification,
            finalGradeLabel,
            null,
            null,
            null,
            null,
            null
        );
    }

    private void addVintageClassification(String value) {
        jdbcTemplate.update(
            "INSERT INTO sys_dict_data (dict_code, dict_sort, dict_value, dict_type, status) VALUES (?, ?, ?, ?, ?)",
            count("sys_dict_data") + 1L,
            10,
            value,
            NxrDictionaryService.VINTAGE_CLASSIFICATION_DICT,
            "0"
        );
    }

    private int count(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private void resetSchema() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_submission (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                cert_id VARCHAR(32) NOT NULL UNIQUE,
                product_type_code VARCHAR(32) NOT NULL DEFAULT 'graded_card',
                vintage_classification_code VARCHAR(64),
                merch_description TEXT,
                card_category_code VARCHAR(32) NOT NULL DEFAULT 'trading_card',
                card_name VARCHAR(255) NOT NULL,
                movie_name VARCHAR(255),
                release_year VARCHAR(16),
                production_company VARCHAR(128),
                film_type VARCHAR(128),
                sports_type VARCHAR(64),
                group_name VARCHAR(128),
                year_label VARCHAR(16),
                brand_name VARCHAR(64) NOT NULL,
                player_name VARCHAR(128),
                variety_name VARCHAR(255),
                set_name VARCHAR(255) NOT NULL,
                card_number VARCHAR(64) NOT NULL,
                language_code VARCHAR(16) NOT NULL DEFAULT 'EN',
                population_value INT NOT NULL DEFAULT 1,
                status_code VARCHAR(32) NOT NULL DEFAULT 'pending',
                grading_phase_code VARCHAR(32) NOT NULL DEFAULT 'human_review',
                approval_sequence BIGINT,
                entry_notes TEXT,
                entry_by_user_id BIGINT,
                entry_by_label VARCHAR(128),
                approved_by_user_id BIGINT,
                approved_at TIMESTAMP,
                published_at TIMESTAMP,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_score (
                submission_id BIGINT PRIMARY KEY,
                centering_score DECIMAL(4,1) NOT NULL,
                edges_score DECIMAL(4,1) NOT NULL,
                corners_score DECIMAL(4,1) NOT NULL,
                surface_score DECIMAL(4,1) NOT NULL,
                final_grade_value DECIMAL(4,2) NOT NULL,
                final_grade_label VARCHAR(64) NOT NULL,
                ai_grade_value DECIMAL(4,1),
                ai_confidence_value DECIMAL(5,2),
                decision_method_code VARCHAR(32) NOT NULL,
                decision_notes TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE submission_media (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                submission_id BIGINT NOT NULL,
                media_side_code VARCHAR(16) NOT NULL,
                media_stage_code VARCHAR(16) NOT NULL,
                public_url VARCHAR(255),
                sort_order INT NOT NULL DEFAULT 1,
                is_active TINYINT NOT NULL DEFAULT 1
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE sys_user (
                user_id BIGINT PRIMARY KEY,
                user_name VARCHAR(64),
                nick_name VARCHAR(64)
            )
            """
        );
        jdbcTemplate.update(
            "INSERT INTO sys_user (user_id, user_name, nick_name) VALUES (1, 'admin', 'NXR Admin')"
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE sys_dict_data (
                dict_code BIGINT PRIMARY KEY,
                dict_sort INT NOT NULL,
                dict_value VARCHAR(100) NOT NULL,
                dict_type VARCHAR(100) NOT NULL,
                status CHAR(1) NOT NULL
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE nxr_python_match_projection (
                source_code VARCHAR(16) NOT NULL,
                cert_id VARCHAR(32) NOT NULL,
                product_type_code VARCHAR(32) NOT NULL,
                card_category_code VARCHAR(32) NOT NULL,
                set_name VARCHAR(255) NOT NULL,
                card_number VARCHAR(64) NOT NULL,
                card_name VARCHAR(255) NOT NULL,
                brand_name VARCHAR(64) NOT NULL,
                year_label VARCHAR(16),
                variety_name VARCHAR(255),
                language_code VARCHAR(16),
                sports_type VARCHAR(64),
                group_name VARCHAR(128),
                merch_description TEXT,
                status_code VARCHAR(32),
                source_updated_at TIMESTAMP,
                PRIMARY KEY (source_code, cert_id)
            )
            """
        );
    }
}
