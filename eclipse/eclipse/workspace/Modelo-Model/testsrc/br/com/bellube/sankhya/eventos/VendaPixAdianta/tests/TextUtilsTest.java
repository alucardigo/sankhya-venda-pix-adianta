package br.com.bellube.sankhya.eventos.VendaPixAdianta.tests;

import br.com.bellube.sankhya.eventos.VendaPixAdianta.action.TextUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TextUtilsTest {

    @Test
    public void asciiSanitizeShouldRemoveAccentsAndKeepAscii() {
        String input = "Árvore – café ‘teste’\nLinha2";
        String sanitized = TextUtils.asciiSanitize(input);
        Assertions.assertEquals("Arvore - cafe 'teste'\nLinha2", sanitized);
    }

    @Test
    public void extractFileKeyShouldParseXmlAndJson() {
        String xml = "<valor>abc123</valor>";
        String json = "{\"chaveArquivo\":\"def456\"}";
        String json2 = "{\"chaveArquivo\":{\"$\":\"ghi789\"}}";
        Assertions.assertEquals("abc123", TextUtils.extractFileKey(xml));
        Assertions.assertEquals("def456", TextUtils.extractFileKey(json));
        Assertions.assertEquals("ghi789", TextUtils.extractFileKey(json2));
    }
}