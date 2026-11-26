package com.leo.trashvpn;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.assertEquals;

public class AppLanguageTest {
    @Test public void choosesChineseScriptRatherThanTreatingAllChineseLocalesAlike() {
        assertEquals(AppLanguage.SIMPLIFIED,AppLanguage.fromSystem(Locale.forLanguageTag("zh-CN")));
        assertEquals(AppLanguage.SIMPLIFIED,AppLanguage.fromSystem(Locale.forLanguageTag("zh-Hans-TW")));
        for(String tag:new String[]{"zh-TW","zh-HK","zh-MO","zh-Hant-US"})
            assertEquals(AppLanguage.TRADITIONAL,AppLanguage.fromSystem(Locale.forLanguageTag(tag)));
    }

    @Test public void supportsPersianAndDefaultsToEnglishForOtherLanguages() {
        assertEquals(AppLanguage.PERSIAN,AppLanguage.fromSystem(Locale.forLanguageTag("fa-IR")));
        assertEquals(AppLanguage.ENGLISH,AppLanguage.fromSystem(Locale.UK));
        assertEquals(AppLanguage.ENGLISH,AppLanguage.fromSystem(Locale.FRANCE));
        assertEquals(AppLanguage.ENGLISH,AppLanguage.fromSystem(Locale.JAPAN));
    }
}
