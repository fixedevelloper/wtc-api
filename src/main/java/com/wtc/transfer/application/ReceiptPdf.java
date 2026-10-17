package com.wtc.transfer.application;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reçu de transfert au format PDF, établi <b>d'après l'instantané figé</b> du transfert : ce que le
 * client a validé, avec les numéros masqués. Police Helvetica (alphabet latin) : les noms dans
 * d'autres alphabets ne s'y afficheraient pas.
 */
final class ReceiptPdf {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'", Locale.FRANCE)
                    .withZone(ZoneOffset.UTC);
    private static final Font TITLE = new Font(Font.HELVETICA, 18, Font.BOLD);
    private static final Font BOLD = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.ITALIC);

    private ReceiptPdf() {}

    static byte[] render(JsonMapper json, TransferRow t) {
        JsonNode snapshot = json.readTree(t.snapshotJson());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.addTitle("Reçu " + t.reference());
            document.addCreator("WTC");
            Paragraph title = new Paragraph("WTC — Reçu de transfert", TITLE);
            title.setSpacingAfter(12);
            document.add(title);

            PdfPTable table = new PdfPTable(new float[] {2f, 3f});
            table.setWidthPercentage(100);
            row(table, "Référence", t.reference());
            row(table, "Date", DATE.format(t.createdAt()));
            row(table, "Statut", status(t));
            if (t.completedAt() != null) {
                row(table, "Payé le", DATE.format(t.completedAt()));
            }
            row(table, "Expéditeur", name(snapshot.path("sender")));
            row(table, "Bénéficiaire", name(snapshot.path("beneficiary")));
            row(table, "Destination", destination(snapshot.path("destination")));
            row(table, "Montant envoyé", money(t.sourceAmount(), t.sourceCurrency()));
            row(table, "Frais", money(t.feeAmount(), t.sourceCurrency()));
            row(table, "Total débité", money(t.totalDebit(), t.sourceCurrency()));
            if (t.fxRate() != null && !t.sourceCurrency().equals(t.destinationCurrency())) {
                row(
                        table,
                        "Taux",
                        "1 "
                                + t.sourceCurrency()
                                + " = "
                                + t.fxRate().stripTrailingZeros().toPlainString()
                                + " "
                                + t.destinationCurrency());
            }
            if (t.destinationAmount() != null) {
                row(
                        table,
                        "Montant reçu par le bénéficiaire",
                        money(t.destinationAmount(), t.destinationCurrency()));
            }
            if (t.comment() != null && !t.comment().isBlank()) {
                row(table, "Message", t.comment());
            }
            document.add(table);

            Paragraph note = new Paragraph(new Chunk(note(t), SMALL));
            note.setSpacingBefore(18);
            document.add(note);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Génération du reçu impossible", e);
        }
        return out.toByteArray();
    }

    private static String note(TransferRow t) {
        return switch (t.status()) {
            case COMPLETED ->
                    "Ce transfert a été payé au bénéficiaire. Conservez ce reçu ; la référence"
                            + " ci-dessus identifie votre opération.";
            case REFUNDED ->
                    "Ce transfert n'a pas abouti : le montant débité a été recrédité sur votre"
                            + " portefeuille.";
            case FAILED, CANCELLED ->
                    "Ce transfert n'a pas abouti. Aucun paiement n'a été effectué au bénéficiaire.";
            default ->
                    "Ce transfert est en cours de traitement : ce document ne prouve pas que le"
                            + " bénéficiaire a été payé.";
        };
    }

    private static String status(TransferRow t) {
        return switch (t.status()) {
            case COMPLETED -> "Payé";
            case REFUNDED -> "Remboursé";
            case FAILED -> "Échoué";
            case CANCELLED -> "Annulé";
            default -> "En cours de traitement";
        };
    }

    private static String name(JsonNode party) {
        if ("BUSINESS".equals(party.path("kind").asString())) {
            return party.path("businessName").asString("");
        }
        return (party.path("firstName").asString("") + " " + party.path("lastName").asString(""))
                .trim();
    }

    private static String destination(JsonNode d) {
        String where =
                d.has("bank")
                        ? d.get("bank").path("name").asString("")
                        : d.has("mobileOperator")
                                ? d.get("mobileOperator").path("name").asString("")
                                : "";
        return (where + " " + d.path("accountNumberMasked").asString("")).trim()
                + " ("
                + d.path("countryCode").asString("")
                + ")";
    }

    private static String money(BigDecimal amount, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return amount.setScale(digits, java.math.RoundingMode.UNNECESSARY).toPlainString()
                + " "
                + currency;
    }

    private static void row(PdfPTable table, String label, String value) {
        PdfPCell left = new PdfPCell(new Phrase(label, BOLD));
        PdfPCell right = new PdfPCell(new Phrase(value, NORMAL));
        for (PdfPCell cell : new PdfPCell[] {left, right}) {
            cell.setPadding(6);
            cell.setBorderWidth(0.5f);
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        }
        table.addCell(left);
        table.addCell(right);
    }
}
