package br.com.bellube.sankhya.eventos.VendaPixAdianta.tests;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AdiantamentoTask;
import br.com.bellube.sankhya.eventos.VendaPixAdianta.async.AsyncAdiantamentoProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class AsyncSubmissionTest {

    @Test
    public void shouldSubmitTaskAndNotBlock() {
        AdiantamentoTask task = new AdiantamentoTask(
                new BigDecimal("123"),
                new BigDecimal("456"),
                new BigDecimal("789.00"),
                new Timestamp(System.currentTimeMillis()),
                new BigDecimal("1"),
                new BigDecimal("20200")
        );
        long start = System.currentTimeMillis();
        AsyncAdiantamentoProcessor.submitTask(task);
        long duration = System.currentTimeMillis() - start;
        Assertions.assertTrue(duration < 50, "Submission should be non-blocking");
        String metrics = AsyncAdiantamentoProcessor.getSystemMetrics();
        Assertions.assertTrue(metrics.contains("Submitted"));
    }
}
