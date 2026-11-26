package com.leo.trashvpn;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DiagnosticLogTest {
    @Test public void keepsTheMostRecentLinesInOrder() {
        DiagnosticLog.History log=new DiagnosticLog.History();
        int limit=DiagnosticLog.MAX_LINES;
        for(int i=0;i<limit+2;i++) log.add("优选记录 "+i);
        String[] lines=log.text().split("\n");
        assertEquals(limit,lines.length);
        assertEquals("优选记录 2",lines[0]);
        assertEquals("优选记录 "+(limit+1),lines[limit-1]);
    }

    @Test public void capsMultibyteHistoryForStorageAndSharing() {
        DiagnosticLog.History log=new DiagnosticLog.History();
        for(int i=0;i<DiagnosticLog.MAX_LINES;i++) log.add("连".repeat(280));
        log.add("最新一条");
        assertTrue(log.text().getBytes(StandardCharsets.UTF_8).length<=DiagnosticLog.MAX_BYTES);
        assertTrue(log.text().endsWith("最新一条\n"));
    }
}
