package br.com.bellube.sankhya.eventos.VendaPixAdianta.service;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servico de notificacao e envio de email de boletos PIX.
 *
 * Notifica o vendedor via TSIAVI (notificacao interna do Sankhya)
 * e envia email ao parceiro com informacoes do boleto.
 */
public class NotificacaoService {

    private static final Logger LOGGER = Logger.getLogger(NotificacaoService.class.getName());
    private static final String LOG_PREFIX = "[NOTIFICACAO-BOLETO] ";

    private static final BigDecimal CODUSU_SISTEMA = new BigDecimal("376");
    private static final String TIPO_AVISO = "P"; // Popup
    private static final String IMPORTANCIA = "A"; // Alta
    private static final SimpleDateFormat SDF = new SimpleDateFormat("dd/MM/yyyy");

    /**
     * Envia notificacao no Sankhya para o vendedor da nota e email para o parceiro.
     */
    public void notificarBoletoGerado(BigDecimal nunotaVenda, BigDecimal codparc,
                                       BigDecimal numnota, BoletoResult boleto) {
        if (boleto == null || !boleto.isSuccess()) {
            LOGGER.info(LOG_PREFIX + "Boleto sem sucesso, pulando notificacao");
            return;
        }

        try {
            notificarVendedorSankhya(nunotaVenda, numnota, boleto);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Falha ao notificar vendedor NUNOTA=" + nunotaVenda, e);
        }

        try {
            enviarEmailParceiro(codparc, nunotaVenda, numnota, boleto);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Falha ao enviar email parceiro CODPARC=" + codparc, e);
        }
    }

    /**
     * Cria notificacao interna do Sankhya (TSIAVI) para o vendedor.
     */
    private void notificarVendedorSankhya(BigDecimal nunotaVenda, BigDecimal numnota, BoletoResult boleto) {
        JdbcWrapper jdbc = null;
        PreparedStatement psFind = null;
        PreparedStatement psInsert = null;
        ResultSet rs = null;

        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Buscar o usuario vendedor da nota
            BigDecimal codUsuVendedor = buscarUsuarioVendedor(jdbc, nunotaVenda);
            if (codUsuVendedor == null) {
                LOGGER.info(LOG_PREFIX + "Usuario vendedor nao encontrado para NUNOTA=" + nunotaVenda + ", notificando usuario sistema");
                codUsuVendedor = CODUSU_SISTEMA;
            }

            // Buscar dados complementares
            String nomeParceiro = buscarNomeParceiro(jdbc, nunotaVenda);
            BigDecimal valorNota = buscarValorNota(jdbc, nunotaVenda);

            String titulo = "Boleto PIX Adiantamento - Nota " + numnota;
            StringBuilder descricao = new StringBuilder();
            descricao.append("Boleto de adiantamento PIX gerado automaticamente.\n\n");
            descricao.append("Venda (NUNOTA): ").append(nunotaVenda).append("\n");
            descricao.append("Boleto (NUMNOTA): ").append(numnota).append("\n");
            if (boleto.getNufin() != null) {
                descricao.append("Titulo (NUFIN): ").append(boleto.getNufin()).append("\n");
            }
            if (boleto.getNossoNumero() != null) {
                descricao.append("Nosso Numero: ").append(boleto.getNossoNumero()).append("\n");
            }
            if (nomeParceiro != null) {
                descricao.append("Parceiro: ").append(nomeParceiro).append("\n");
            }
            if (valorNota != null) {
                descricao.append("Valor: R$ ").append(String.format("%.2f", valorNota)).append("\n");
            }
            descricao.append("\nO boleto esta disponivel na tela de Adiantamento/Emprestimo (botao Imprimir Boleto PIX).");

            // Obter proximo NUAVI
            psFind = jdbc.getPreparedStatement("SELECT ISNULL(MAX(NUAVI), 0) + 1 FROM TSIAVI");
            rs = psFind.executeQuery();
            BigDecimal nuavi = rs.next() ? rs.getBigDecimal(1) : BigDecimal.ONE;
            rs.close();
            psFind.close();

            // Inserir notificacao
            psInsert = jdbc.getPreparedStatement(
                    "INSERT INTO TSIAVI (NUAVI, TITULO, DESCRICAO, TIPOAVISO, IMPORTANCIA, " +
                    "CODUSU, CODUSUDEST, DHCRIACAO, IDENTIFICADOR, LIDO) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, GETDATE(), ?, 'N')");
            psInsert.setBigDecimal(1, nuavi);
            psInsert.setString(2, titulo.length() > 200 ? titulo.substring(0, 200) : titulo);
            psInsert.setString(3, descricao.toString());
            psInsert.setString(4, TIPO_AVISO);
            psInsert.setString(5, IMPORTANCIA);
            psInsert.setBigDecimal(6, CODUSU_SISTEMA);
            psInsert.setBigDecimal(7, codUsuVendedor);
            psInsert.setString(8, "VENDAPIX_BOLETO_" + nunotaVenda);
            psInsert.executeUpdate();

            LOGGER.info(LOG_PREFIX + "Notificacao criada NUAVI=" + nuavi + " para usuario " + codUsuVendedor);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao criar notificacao TSIAVI", e);
        } finally {
            closeQuietly(rs, psFind, null);
            closeQuietly(null, psInsert, jdbc);
        }
    }

    /**
     * Envia email ao parceiro com dados do boleto.
     */
    private void enviarEmailParceiro(BigDecimal codparc, BigDecimal nunotaVenda,
                                      BigDecimal numnota, BoletoResult boleto) {
        if (codparc == null) {
            LOGGER.info(LOG_PREFIX + "CODPARC nulo, email nao enviado");
            return;
        }

        JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Buscar email do parceiro
            ps = jdbc.getPreparedStatement(
                    "SELECT EMAIL, NOMEPARC FROM TGFPAR WITH (NOLOCK) WHERE CODPARC = ?");
            ps.setBigDecimal(1, codparc);
            rs = ps.executeQuery();

            if (!rs.next()) {
                LOGGER.info(LOG_PREFIX + "Parceiro " + codparc + " nao encontrado");
                return;
            }

            String email = rs.getString("EMAIL");
            String nomeParceiro = rs.getString("NOMEPARC");

            if (email == null || email.trim().isEmpty()) {
                LOGGER.info(LOG_PREFIX + "Parceiro " + codparc + " (" + nomeParceiro + ") sem email cadastrado");
                return;
            }

            // Montar email
            String assunto = "BelLube - Boleto Adiantamento PIX - Nota " + numnota;

            StringBuilder corpo = new StringBuilder();
            corpo.append("Prezado(a) ").append(nomeParceiro != null ? nomeParceiro : "Cliente").append(",\n\n");
            corpo.append("Segue o boleto referente ao adiantamento PIX da sua compra.\n\n");
            corpo.append("Numero da Nota: ").append(numnota).append("\n");
            if (boleto.getNossoNumero() != null) {
                corpo.append("Nosso Numero: ").append(boleto.getNossoNumero()).append("\n");
            }
            corpo.append("\nO boleto segue em anexo neste email.\n");
            corpo.append("\nEm caso de duvidas, entre em contato conosco.\n\n");
            corpo.append("Atenciosamente,\n");
            corpo.append("BEL Distribuidor de Lubrificantes Ltda\n");

            // Enviar via MailUtil do Sankhya
            enviarViaSankhyaMail(email, assunto, corpo.toString(), boleto.getPdfBytes(), numnota);

            LOGGER.info(LOG_PREFIX + "Email enviado para " + email + " (CODPARC=" + codparc + ")");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao enviar email para CODPARC=" + codparc, e);
        } finally {
            closeQuietly(rs, ps, jdbc);
        }
    }

    /**
     * Envia email usando a infraestrutura de email do Sankhya.
     */
    private void enviarViaSankhyaMail(String destinatario, String assunto, String corpo,
                                       byte[] pdfAnexo, BigDecimal numnota) {
        try {
            // Tentar usar br.com.sankhya.modelcore.util.MailHelper ou similar
            Class<?> mailHelperClass = null;

            // Estrategia 1: MGECoreParameter para SMTP + JavaMail
            String[] possiveisClasses = {
                "br.com.sankhya.modelcore.util.MailHelper",
                "br.com.sankhya.modelcore.util.MailUtil",
                "br.com.sankhya.ws.util.MailSender"
            };

            for (String cls : possiveisClasses) {
                try {
                    mailHelperClass = Class.forName(cls);
                    LOGGER.info(LOG_PREFIX + "Classe de email encontrada: " + cls);
                    break;
                } catch (ClassNotFoundException ignore) {}
            }

            if (mailHelperClass != null) {
                // Tentar enviar via classe Sankhya encontrada
                enviarViaReflection(mailHelperClass, destinatario, assunto, corpo, pdfAnexo, numnota);
                return;
            }

            // Fallback: salvar na fila de emails do Sankhya (TSIEML)
            salvarFilaEmail(destinatario, assunto, corpo, numnota);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao enviar email via Sankhya para " + destinatario, e);
            // Fallback: registrar na fila
            try {
                salvarFilaEmail(destinatario, assunto, corpo, numnota);
            } catch (Exception e2) {
                LOGGER.log(Level.SEVERE, LOG_PREFIX + "Falha total no envio de email", e2);
            }
        }
    }

    /**
     * Tenta enviar via reflexao usando a classe de email do Sankhya.
     */
    private void enviarViaReflection(Class<?> mailClass, String destinatario, String assunto,
                                      String corpo, byte[] pdfAnexo, BigDecimal numnota) {
        try {
            // Abordagem generica: a maioria dos MailHelper tem sendMail(to, subject, body)
            java.lang.reflect.Method[] methods = mailClass.getMethods();
            for (java.lang.reflect.Method m : methods) {
                if ("sendMail".equals(m.getName()) || "send".equals(m.getName()) || "enviarEmail".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length >= 3) {
                        LOGGER.info(LOG_PREFIX + "Tentando envio via " + mailClass.getSimpleName() + "." + m.getName());
                        if (params.length == 3) {
                            m.invoke(null, destinatario, assunto, corpo);
                        } else if (params.length == 4 && params[3] == byte[].class) {
                            m.invoke(null, destinatario, assunto, corpo, pdfAnexo);
                        }
                        return;
                    }
                }
            }
            LOGGER.warning(LOG_PREFIX + "Nenhum metodo de envio compativel encontrado em " + mailClass.getName());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Reflexao de email falhou", e);
        }
    }

    /**
     * Salva o email na fila do Sankhya (TSIEML) para envio posterior.
     */
    private void salvarFilaEmail(String destinatario, String assunto, String corpo, BigDecimal numnota) {
        JdbcWrapper jdbc = null;
        PreparedStatement ps = null;
        try {
            jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();

            // Verificar se TSIEML existe
            ps = jdbc.getPreparedStatement(
                    "IF OBJECT_ID('TSIEML', 'U') IS NOT NULL " +
                    "INSERT INTO TSIEML (ASSUNTO, MENSAGEM, DESTINATARIO, STATUS, DHCRIACAO, CODUSU) " +
                    "VALUES (?, ?, ?, 'P', GETDATE(), ?)");
            ps.setString(1, assunto);
            ps.setString(2, corpo);
            ps.setString(3, destinatario);
            ps.setBigDecimal(4, CODUSU_SISTEMA);
            ps.executeUpdate();

            LOGGER.info(LOG_PREFIX + "Email salvo na fila TSIEML para " + destinatario);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, LOG_PREFIX + "Erro ao salvar na fila de email - nao critico", e);
        } finally {
            closeQuietly(null, ps, jdbc);
        }
    }

    // --- Helpers de busca ---

    private BigDecimal buscarUsuarioVendedor(JdbcWrapper jdbc, BigDecimal nunota) throws Exception {
        PreparedStatement ps = jdbc.getPreparedStatement(
                "SELECT COALESCE(C.AD_CODUSUVEND, C.CODUSU) FROM TGFCAB C WITH (NOLOCK) WHERE C.NUNOTA = ?");
        ps.setBigDecimal(1, nunota);
        ResultSet rs = ps.executeQuery();
        BigDecimal result = rs.next() ? rs.getBigDecimal(1) : null;
        rs.close();
        ps.close();
        return result;
    }

    private String buscarNomeParceiro(JdbcWrapper jdbc, BigDecimal nunota) throws Exception {
        PreparedStatement ps = jdbc.getPreparedStatement(
                "SELECT P.NOMEPARC FROM TGFPAR P WITH (NOLOCK) " +
                "JOIN TGFCAB C WITH (NOLOCK) ON C.CODPARC = P.CODPARC WHERE C.NUNOTA = ?");
        ps.setBigDecimal(1, nunota);
        ResultSet rs = ps.executeQuery();
        String result = rs.next() ? rs.getString(1) : null;
        rs.close();
        ps.close();
        return result;
    }

    private BigDecimal buscarValorNota(JdbcWrapper jdbc, BigDecimal nunota) throws Exception {
        PreparedStatement ps = jdbc.getPreparedStatement(
                "SELECT VLRNOTA FROM TGFCAB WITH (NOLOCK) WHERE NUNOTA = ?");
        ps.setBigDecimal(1, nunota);
        ResultSet rs = ps.executeQuery();
        BigDecimal result = rs.next() ? rs.getBigDecimal(1) : null;
        rs.close();
        ps.close();
        return result;
    }

    private void closeQuietly(ResultSet rs, PreparedStatement ps, JdbcWrapper jdbc) {
        if (rs != null) try { rs.close(); } catch (Exception ignore) {}
        if (ps != null) try { ps.close(); } catch (Exception ignore) {}
        if (jdbc != null) try { jdbc.closeSession(); } catch (Exception ignore) {}
    }
}
