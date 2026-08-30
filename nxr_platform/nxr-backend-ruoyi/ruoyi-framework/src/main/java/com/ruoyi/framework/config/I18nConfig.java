package com.ruoyi.framework.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import com.ruoyi.common.constant.Constants;

/**
 * 资源文件配置加载
 * 
 * @author ruoyi
 */
@Configuration
public class I18nConfig
{
    @Bean
    public LocaleResolver localeResolver()
    {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Constants.DEFAULT_LOCALE);
        resolver.setSupportedLocales(List.of(Constants.DEFAULT_LOCALE, java.util.Locale.SIMPLIFIED_CHINESE));
        return resolver;
    }
}
