package com.nxr.platform.shared;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 读取若依 sys_dict_data 的业务字典服务。
 *
 * sports_type 等可配置选项由超级管理员在若依「系统管理-字典管理」中维护，
 * 字典类型编码见 {@link #SPORTS_TYPE_DICT}。
 */
@Service
public class NxrDictionaryService {

    public static final String SPORTS_TYPE_DICT = "nxr_sports_type";

    private final JdbcClient jdbcClient;

    public NxrDictionaryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<String> listActiveValues(String dictType) {
        return jdbcClient.sql("""
                SELECT dict_value
                FROM sys_dict_data
                WHERE dict_type = :dictType AND status = '0'
                ORDER BY dict_sort, dict_code
                """)
            .param("dictType", dictType)
            .query(String.class)
            .list();
    }

    /**
     * 将传入值规范化为字典中的标准值（忽略大小写匹配）。
     * 与 Flask 侧 normalize_sports_type 行为一致：匹配不到时保留原值，
     * 以兼容字典调整前录入的历史数据。
     */
    public String normalizeSportsType(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        for (String option : listActiveValues(SPORTS_TYPE_DICT)) {
            if (option != null && option.trim().toLowerCase(Locale.ROOT).equals(lowered)) {
                return option.trim();
            }
        }
        return trimmed;
    }
}
