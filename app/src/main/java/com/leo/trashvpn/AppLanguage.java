package com.leo.trashvpn;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.Locale;

enum AppLanguage {
    SIMPLIFIED("zh-Hans",R.string.language_zh_hans),
    TRADITIONAL("zh-Hant",R.string.language_zh_hant),
    ENGLISH("en",R.string.language_en),
    PERSIAN("fa",R.string.language_fa);

    static final String PREF="ui-language";
    final Locale locale;
    final int label;
    AppLanguage(String tag,int label) {
        locale=Locale.forLanguageTag(tag);
        this.label=label;
    }

    static AppLanguage fromSystem(Locale system) {
        return switch(system.getLanguage()) {
            case "zh" -> {
                if(!system.getScript().isEmpty()) yield "Hant".equals(system.getScript())?TRADITIONAL:SIMPLIFIED;
                String region=system.getCountry();
                yield "TW".equals(region)||"HK".equals(region)||"MO".equals(region)?TRADITIONAL:SIMPLIFIED;
            }
            case "fa" -> PERSIAN;
            default -> ENGLISH;
        };
    }

    static AppLanguage current(Context context) {
        String saved=context.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE).getString(PREF,null);
        for(AppLanguage language:values()) if(language.locale.toLanguageTag().equals(saved)) return language;
        LocaleList system=Resources.getSystem().getConfiguration().getLocales();
        return system.isEmpty()?ENGLISH:fromSystem(system.get(0));
    }

    static Context wrap(Context context) {
        Locale selected=current(context).locale;
        // Keep system density, night mode, etc. Only override the locale list and layout direction.
        Configuration current=context.getResources().getConfiguration();
        if(current.getLocales().size()==1 && selected.equals(current.getLocales().get(0))) return context;
        Configuration override=new Configuration();
        override.setLocales(new LocaleList(selected));
        return context.createConfigurationContext(override);
    }
}
