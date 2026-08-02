package com.nxr.platform.admin;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminBrandSettingsService {

    private final JdbcClient jdbcClient;

    public AdminBrandSettingsService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<BrandSettingResponse> listBrands() {
        return jdbcClient.sql(
                """
                SELECT id, name, aliases, sort_order, is_active, created_at, updated_at
                FROM brand_settings
                ORDER BY sort_order ASC, name ASC
                """
            )
            .query((rs, rowNum) -> new BrandSettingResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("aliases"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .list();
    }

    public BrandSettingResponse createBrand(BrandSettingRequest request) {
        NormalizedBrand normalizedBrand = normalize(request, null);
        try {
            jdbcClient.sql(
                    """
                    INSERT INTO brand_settings (name, aliases, sort_order, is_active)
                    VALUES (:name, :aliases, :sortOrder, :isActive)
                    """
                )
                .params(normalizedBrand.toParams())
                .update();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Brand already exists", exception);
        }

        return findByName(normalizedBrand.name())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Brand was not saved"));
    }

    public BrandSettingResponse updateBrand(long brandId, BrandSettingRequest request) {
        NormalizedBrand normalizedBrand = normalize(request, brandId);
        int updatedRows;
        try {
            updatedRows = jdbcClient.sql(
                    """
                    UPDATE brand_settings
                    SET name = :name,
                        aliases = :aliases,
                        sort_order = :sortOrder,
                        is_active = :isActive,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :brandId
                    """
                )
                .params(normalizedBrand.toParams())
                .param("brandId", brandId)
                .update();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Brand already exists", exception);
        }

        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brand not found");
        }

        return findById(brandId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brand not found"));
    }

    private java.util.Optional<BrandSettingResponse> findById(long brandId) {
        return jdbcClient.sql(
                """
                SELECT id, name, aliases, sort_order, is_active, created_at, updated_at
                FROM brand_settings
                WHERE id = :brandId
                """
            )
            .param("brandId", brandId)
            .query((rs, rowNum) -> new BrandSettingResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("aliases"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .optional();
    }

    private java.util.Optional<BrandSettingResponse> findByName(String brandName) {
        return jdbcClient.sql(
                """
                SELECT id, name, aliases, sort_order, is_active, created_at, updated_at
                FROM brand_settings
                WHERE LOWER(name) = LOWER(:brandName)
                """
            )
            .param("brandName", brandName)
            .query((rs, rowNum) -> new BrandSettingResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("aliases"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .optional();
    }

    private NormalizedBrand normalize(BrandSettingRequest request, Long currentBrandId) {
        String name = clean(request.name());
        String aliases = clean(request.aliases());

        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Brand name is required");
        }
        if (!isAscii(name) || !isAscii(aliases)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Brand name and aliases must use English text only");
        }
        if (findByName(name).filter(existing -> currentBrandId == null || existing.id() != currentBrandId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Brand already exists");
        }

        int sortOrder = request.sortOrder() == null ? nextSortOrder() : Math.max(0, request.sortOrder());
        boolean isActive = request.isActive() == null || request.isActive();
        return new NormalizedBrand(name, aliases, sortOrder, isActive);
    }

    private int nextSortOrder() {
        Integer maxSortOrder = jdbcClient.sql("SELECT COALESCE(MAX(sort_order), 0) FROM brand_settings")
            .query(Integer.class)
            .single();
        return maxSortOrder + 1;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(character -> character == '\n' || character == '\r' || character == '\t' || (character >= 32 && character <= 126));
    }

    public record BrandSettingRequest(
        String name,
        String aliases,
        Integer sortOrder,
        Boolean isActive
    ) {
    }

    public record BrandSettingResponse(
        long id,
        String name,
        String aliases,
        int sortOrder,
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    private record NormalizedBrand(
        String name,
        String aliases,
        int sortOrder,
        boolean isActive
    ) {
        Map<String, Object> toParams() {
            return Map.of(
                "name", name,
                "aliases", aliases,
                "sortOrder", sortOrder,
                "isActive", isActive ? 1 : 0
            );
        }
    }
}
