package com.mybot.eod_bot.bot; // <-- CORRECTED PACKAGE

import com.mybot.eod_bot.model.*;
import com.mybot.eod_bot.repository.*;
import com.mybot.eod_bot.service.ConversationService;
import com.mybot.eod_bot.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MyEodBot extends TelegramLongPollingBot {

    // Spring auto-injects all our services and repos
    private final DistributorRepository distributorRepository;
    private final VendorRepository vendorRepository;
    private final LapuSimRepository lapuSimRepository;
    private final DailyReportRepository dailyReportRepository;
    private final ConversationService conversationService;
    private final ReportService reportService;

    @Value("${telegram.bot.username}")
    private String botUsername;

    // Constructor injection
    public MyEodBot(@Value("${telegram.bot.token}") String botToken,
                    DistributorRepository distributorRepository,
                    VendorRepository vendorRepository,
                    LapuSimRepository lapuSimRepository,
                    DailyReportRepository dailyReportRepository,
                    ConversationService conversationService,
                    ReportService reportService) {
        super(botToken);
        this.distributorRepository = distributorRepository;
        this.vendorRepository = vendorRepository;
        this.lapuSimRepository = lapuSimRepository;
        this.dailyReportRepository = dailyReportRepository;
        this.conversationService = conversationService;
        this.reportService = reportService;
    }

    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                long chatId = update.getMessage().getChatId();
                String text = update.getMessage().getText();
                String state = conversationService.getState(chatId);

                if (state != null) handleConversation(chatId, text, state);
                else if (text.startsWith("/")) handleCommand(chatId, text);

            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery().getMessage().getChatId(), update.getCallbackQuery().getData());

            } else if (update.hasMessage() && update.getMessage().hasDocument()) {
                handleDocument(update.getMessage().getChatId(), update.getMessage().getDocument());
            }
        } catch (Exception e) {
            log.error("Error processing update: {}", e.getMessage(), e);
            if (update.hasMessage()) sendText(update.getMessage().getChatId(), "An error occurred: " + e.getMessage());
        }
    }

    // --- 1. Command Handling ---
    private void handleCommand(long chatId, String command) {
        switch (command) {
            case "/start":
                sendText(chatId, "Welcome! I am your EOD Bot. Commands:\n" +
                        "**Setup:**\n" +
                        "  `/add_distributor` - Add a new distributor deal\n" +
                        "  `/add_vendor` - Add a new vendor discount\n" +
                        "  `/assign_sim` - Map a LAPU SIM to a distributor\n" +
                        "**Daily Use:**\n" +
                        "  `/run_purchases` - Start the purchase import\n" +
                        "  `/run_sales` - Start the vendor sales entry\n" +
                        "  `/get_pnl` - Get today's P&L status\n" +
                        "**Reporting:**\n" +
                        "  `/monthly_report` - Get a full monthly P&L\n" +
                        "**Other:**\n" +
                        "  `/cancel` - Cancel current operation");
                break;
            case "/add_distributor":
                conversationService.setTempData(chatId, new Distributor());
                conversationService.setState(chatId, "AWAIT_DIST_NAME");
                sendText(chatId, "What is the Distributor's Name?");
                break;
            case "/add_vendor":
                conversationService.setTempData(chatId, new Vendor());
                conversationService.setState(chatId, "AWAIT_VENDOR_NAME");
                sendText(chatId, "What is the Vendor's Name?");
                break;
            case "/assign_sim":
                sendDistributorList(chatId);
                break;
            case "/run_purchases":
                conversationService.setState(chatId, "AWAIT_STAT_CSV");
                sendText(chatId, "Please upload your `...mrobo_stat.csv` file to log purchases.");
                break;
            case "/run_sales":
                startSalesFlow(chatId);
                break;
            case "/get_pnl":
                getPnl(chatId, LocalDate.now());
                break;
            case "/monthly_report":
                conversationService.setState(chatId, "AWAIT_MONTH_QUERY");
                sendText(chatId, "Please enter the month to report (e.g., 2025-10):");
                break;
            case "/cancel":
                conversationService.clearState(chatId);
                sendText(chatId, "Operation cancelled.");
                break;
            default:
                sendText(chatId, "Unknown command. Try /start");
        }
    }

    // --- 2. State-Based Conversation Handling ---
    private void handleConversation(long chatId, String text, String state) {
        try {
            switch (state) {
                // Add Distributor Flow
                case "AWAIT_DIST_NAME":
                    Distributor dist = conversationService.getTempData(chatId, Distributor.class);
                    dist.setName(text);
                    conversationService.setState(chatId, "AWAIT_DIST_GET");
                    sendText(chatId, "What is the 'base get' amount? (e.g., 515)");
                    break;
                case "AWAIT_DIST_GET":
                    dist = conversationService.getTempData(chatId, Distributor.class);
                    dist.setBaseGet(Double.parseDouble(text));
                    conversationService.setState(chatId, "AWAIT_DIST_PAY");
                    sendText(chatId, "What is the 'base pay' amount? (e.g., 505)");
                    break;
                case "AWAIT_DIST_PAY":
                    dist = conversationService.getTempData(chatId, Distributor.class);
                    dist.setBasePay(Double.parseDouble(text));
                    distributorRepository.save(dist);
                    conversationService.clearState(chatId);
                    sendText(chatId, "✅ Distributor '" + dist.getName() + "' saved!");
                    break;

                // Add Vendor Flow
                case "AWAIT_VENDOR_NAME":
                    Vendor vendor = conversationService.getTempData(chatId, Vendor.class);
                    vendor.setName(text);
                    conversationService.setState(chatId, "AWAIT_VENDOR_DISCOUNT");
                    sendText(chatId, "What is their discount percent? (e.g., 1.5)");
                    break;
                case "AWAIT_VENDOR_DISCOUNT":
                    vendor = conversationService.getTempData(chatId, Vendor.class);
                    vendor.setDiscountPercent(Double.parseDouble(text));
                    vendorRepository.save(vendor);
                    conversationService.clearState(chatId);
                    sendText(chatId, "✅ Vendor '" + vendor.getName() + "' saved!");
                    break;

                // Assign SIM Flow
                case "AWAIT_SIM_NO":
                    Long distId = conversationService.getTempData(chatId, Long.class);
                    Distributor assignedDist = distributorRepository.findById(distId)
                            .orElseThrow(() -> new RuntimeException("Distributor not found"));

                    LapuSim sim = new LapuSim();
                    sim.setLapuNo(text.trim()); // The SIM number is the ID
                    sim.setDistributor(assignedDist);

                    lapuSimRepository.save(sim);
                    conversationService.clearState(chatId);
                    sendText(chatId, "✅ SIM `" + text + "` is now assigned to '" + assignedDist.getName() + "'.");
                    break;

                // Monthly Report Flow
                case "AWAIT_MONTH_QUERY":
                    runMonthlyReport(chatId, text);
                    conversationService.clearState(chatId);
                    break;

                // P&L Sales Entry Flow
                case "AWAIT_VENDOR_PREV_BAL":
                    Map<String, Object> reportData = conversationService.getTempData(chatId, Map.class);
                    reportData.put("current_prev_bal", Double.parseDouble(text));
                    conversationService.setState(chatId, "AWAIT_VENDOR_TOPUP");
                    sendText(chatId, "2. Enter TODAY'S Total Top-Ups:");
                    break;
                case "AWAIT_VENDOR_TOPUP":
                    reportData = conversationService.getTempData(chatId, Map.class);
                    reportData.put("current_topup", Double.parseDouble(text));
                    conversationService.setState(chatId, "AWAIT_VENDOR_END_BAL");
                    sendText(chatId, "3. Enter TODAY'S End Balance:");
                    break;
                case "AWAIT_VENDOR_END_BAL":
                    reportData = conversationService.getTempData(chatId, Map.class);
                    reportData.put("current_end_bal", Double.parseDouble(text));
                    processVendorSale(chatId); // This will do the math and ask for the next vendor
                    break;
            }
        } catch (NumberFormatException e) {
            sendText(chatId, "Invalid number. Please try again. Use /cancel to stop.");
        } catch (Exception e) {
            log.error("Conversation error: {}", e.getMessage(), e);
            sendText(chatId, "An error occurred: " + e.getMessage() + ". Use /cancel to stop.");
        }
    }

    // --- 3. Callback (Inline Button) Handling ---
    private void handleCallbackQuery(long chatId, String data) {
        if (data.startsWith("assign_sim_dist_")) {
            Long distId = Long.parseLong(data.substring("assign_sim_dist_".length()));
            conversationService.setTempData(chatId, distId);
            conversationService.setState(chatId, "AWAIT_SIM_NO");
            sendText(chatId, "Please enter the LAPU SIM Number (`Lapu No`):");
        }
    }

    private void sendDistributorList(long chatId) {
        List<Distributor> distributors = distributorRepository.findAll();
        if (distributors.isEmpty()) {
            sendText(chatId, "No distributors found. Please /add_distributor first.");
            return;
        }

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder = InlineKeyboardMarkup.builder();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Distributor dist : distributors) {
            rows.add(List.of(
                    InlineKeyboardButton.builder()
                            .text(dist.getName())
                            .callbackData("assign_sim_dist_" + dist.getId())
                            .build()
            ));
        }
        keyboardBuilder.keyboard(rows);

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Please select a distributor to assign this SIM to:")
                .replyMarkup(keyboardBuilder.build())
                .build();
        executeMessage(message);
    }

    // --- 4. Document Upload Handling (for /run_purchases) ---
    private void handleDocument(long chatId, Document document) {
        String state = conversationService.getState(chatId);
        if (!"AWAIT_STAT_CSV".equals(state)) {
            sendText(chatId, "I wasn't expecting a file. Please start a command like /run_purchases first.");
            return;
        }

        sendText(chatId, "File received! Parsing purchases...");
        try {
            // 1. Download the file from Telegram
            GetFile getFile = new GetFile(document.getFileId());
            org.telegram.telegrambots.meta.api.objects.File telegramFile = execute(getFile);
            File localFile = downloadFile(telegramFile);

            // 2. Load all our SIM/Distributor data
            Map<String, LapuSim> simMap = lapuSimRepository.findAll().stream()
                    .collect(Collectors.toMap(LapuSim::getLapuNo, sim -> sim));

            if (simMap.isEmpty()) {
                sendText(chatId, "Error: No SIMs found in database. Please /assign_sim first.");
                conversationService.clearState(chatId);
                return;
            }

            // 3. Parse the CSV
            Reader in = new FileReader(localFile);

            // This line was corrected in the previous step
            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build()
                    .parse(in);

            Map<String, DailyPurchaseReport> purchaseReportsMap = new HashMap<>();
            List<String> unassignedSims = new ArrayList<>();
            double totalLoadReceived = 0;
            double totalCostPayable = 0;

            for (CSVRecord row : parser) {
                long openBal = Long.parseLong(row.get("Open Bal"));
                long totalAmount = Long.parseLong(row.get("Total Amount"));
                long closeBal = Long.parseLong(row.get("Close Bal"));
                String lapuNo = row.get("Lapu No").split("\\.")[0]; // Clean "1234.0" to "1234"

                double loadReceived = (double) closeBal - openBal + totalAmount;

                if (loadReceived <= 0) continue; // Skip rows with no purchases

                LapuSim sim = simMap.get(lapuNo);
                if (sim == null) {
                    unassignedSims.add(lapuNo + " (" + row.get("Desc") + ")");
                    continue;
                }

                Distributor dist = sim.getDistributor();
                double payableFactor = (dist.getBaseGet() == 0) ? 0 : (dist.getBasePay() / dist.getBaseGet());
                double costPayable = loadReceived * payableFactor;

                totalLoadReceived += loadReceived;
                totalCostPayable += costPayable;

                DailyPurchaseReport report = purchaseReportsMap.computeIfAbsent(dist.getName(), k -> new DailyPurchaseReport());
                report.setDistributorName(dist.getName());
                report.setTotalLoadReceived(report.getTotalLoadReceived() + loadReceived);
                report.setTotalCostPayable(report.getTotalCostPayable() + costPayable);
            }

            double masterCostFactor = (totalLoadReceived == 0) ? 0 : (totalCostPayable / totalLoadReceived);

            // 4. Get today's report & update it
            DailyReport dailyReport = reportService.getOrCreateDailyReport(LocalDate.now());
            String pnlStatus = reportService.updatePurchases(dailyReport, new ArrayList<>(purchaseReportsMap.values()), masterCostFactor);

            // 5. Send summary to user
            StringBuilder sb = new StringBuilder();
            sb.append("✅ **Purchases Logged:**\n");
            sb.append(String.format("- Total Load: %.2f INR\n", totalLoadReceived));
            sb.append(String.format("- Total Cost: %.2f INR\n", totalCostPayable));
            sb.append(String.format("- Daily Cost Factor: %.6f\n\n", masterCostFactor));

            if (!unassignedSims.isEmpty()) {
                sb.append("**(!) Unassigned SIMs found:**\n");
                unassignedSims.stream().distinct().limit(10).forEach(s -> sb.append("- `").append(s).append("`\n"));
                if(unassignedSims.size() > 10) sb.append("...and more.\n");
                sb.append("*Use /assign_sim to fix.*\n\n");
            }

            sb.append(pnlStatus); // Add the P&L status
            sendText(chatId, sb.toString());
            conversationService.clearState(chatId);

        } catch (Exception e) {
            log.error("CSV Parsing failed: {}", e.getMessage(), e);
            sendText(chatId, "Error parsing file: " + e.getMessage());
            conversationService.clearState(chatId);
        }
    }

    // --- 5. P&L Sales Entry Flow (for /run_sales) ---
    private void startSalesFlow(long chatId) {
        DailyReport dailyReport = reportService.getOrCreateDailyReport(LocalDate.now());
        if (!dailyReport.isHasPurchaseData()) {
            sendText(chatId, "Warning: You haven't run `/run_purchases` yet.\n" +
                    "I can't calculate your *true* profit until I know today's cost factor.\n" +
                    "You can continue, but P&L will only be calculated after you run purchases.");
        }

        List<Vendor> vendors = vendorRepository.findAll();
        if (vendors.isEmpty()) {
            sendText(chatId, "No vendors found. Please /add_vendor first.");
            return;
        }

        Map<String, Object> reportData = new HashMap<>();
        reportData.put("vendors", vendors);
        reportData.put("vendorIndex", 0);
        reportData.put("vendorReports", new ArrayList<DailyVendorReport>());

        conversationService.setTempData(chatId, reportData);
        askForVendorBalance(chatId, vendors.get(0));
    }

    private void askForVendorBalance(long chatId, Vendor vendor) {
        conversationService.setState(chatId, "AWAIT_VENDOR_PREV_BAL");
        Map<String, Object> reportData = conversationService.getTempData(chatId, Map.class);
        reportData.put("currentVendor", vendor);
        sendText(chatId, "--- Processing Vendor: **" + vendor.getName() + " (" + vendor.getDiscountPercent() + "%)** ---\n" +
                "1. Enter YESTERDAY'S End Balance:");
    }

    private void processVendorSale(long chatId) {
        Map<String, Object> reportData = conversationService.getTempData(chatId, Map.class);
        DailyReport dailyReport = reportService.getOrCreateDailyReport(LocalDate.now());

        Vendor vendor = (Vendor) reportData.get("currentVendor");
        double prevBal = (Double) reportData.get("current_prev_bal");
        double topup = (Double) reportData.get("current_topup");
        double endBal = (Double) reportData.get("current_end_bal");
        double costFactor = dailyReport.getMasterCostFactor(); // Get today's cost factor
        List<DailyVendorReport> vendorReports = (List<DailyVendorReport>) reportData.get("vendorReports");

        // --- Do the P&L Math ---
        double grossRevenue = (prevBal + topup) - endBal;
        double discountFactor = 1 - (vendor.getDiscountPercent() / 100);
        double totalLoadSold = (discountFactor == 0) ? 0 : (grossRevenue / discountFactor);
        double cogs = totalLoadSold * costFactor; // Use the master cost factor
        double netProfit = grossRevenue - cogs;

        // Save this vendor's report
        DailyVendorReport vr = new DailyVendorReport();
        vr.setVendorName(vendor.getName());
        vr.setGrossRevenue(reportService.round(grossRevenue));
        vr.setTotalLoadSold(reportService.round(totalLoadSold));
        vr.setCogs(reportService.round(cogs));
        vr.setNetProfit(reportService.round(netProfit));
        vendorReports.add(vr);

        sendText(chatId, String.format("✅ %s Logged. (Profit: %.2f)", vendor.getName(), vr.getNetProfit()));

        // --- Check for next vendor ---
        int index = (Integer) reportData.get("vendorIndex") + 1;
        List<Vendor> vendors = (List<Vendor>) reportData.get("vendors");

        if (index < vendors.size()) {
            // Ask for next vendor
            reportData.put("vendorIndex", index);
            askForVendorBalance(chatId, vendors.get(index));
        } else {
            // All vendors done, update the main report
            String pnlStatus = reportService.updateSales(dailyReport, vendorReports);
            sendText(chatId, "✅ **All vendor sales logged!**\n" + pnlStatus);
            conversationService.clearState(chatId);
        }
    }

    // --- 6. P&L and Monthly Report Fetching ---
    private void getPnl(long chatId, LocalDate date) {
        DailyReport report = reportService.getOrCreateDailyReport(date);
        StringBuilder sb = new StringBuilder();
        sb.append("📈 **P&L Status for ").append(date).append("** 📈\n\n");

        if (!report.isHasPurchaseData() && !report.isHasSalesData()) {
            sb.append("No data entered for today. Run `/run_purchases` and `/run_sales`.");
            sendText(chatId, sb.toString());
            return;
        }

        if (report.isHasPurchaseData()) {
            sb.append("✅ **Purchase Data is Logged**\n");
            sb.append(String.format("- Master Cost Factor: %.6f\n", report.getMasterCostFactor()));
        } else {
            sb.append("❌ **Purchase Data is Missing**\n");
        }

        if (report.isHasSalesData()) {
            sb.append("✅ **Sales Data is Logged**\n");
        } else {
            sb.append("❌ **Sales Data is Missing**\n");
        }

        if (report.isHasPurchaseData() && report.isHasSalesData()) {
            sb.append("\n**--- P&L Calculated ---**\n");
            sb.append(String.format("- Gross Revenue: %.2f INR\n", report.getTotalGrossRevenue()));
            sb.append(String.format("- Cost of Sales: %.2f INR\n", report.getTotalCogs()));
            sb.append(String.format("- **TOTAL NET PROFIT: %.2f INR**\n", report.getTotalNetProfit()));
        } else {
            sb.append("\n**P&L is pending.** Please submit the missing data.");
        }
        sendText(chatId, sb.toString());
    }

    private void runMonthlyReport(long chatId, String month) {
        try {
            LocalDate startDate = LocalDate.parse(month + "-01");
            LocalDate endDate = startDate.plusMonths(1);

            List<DailyReport> reports = dailyReportRepository.findAll().stream()
                    .filter(r -> r.getDate().isAfter(startDate.minusDays(1)) && r.getDate().isBefore(endDate))
                    .collect(Collectors.toList());

            if (reports.isEmpty()) {
                sendText(chatId, "No reports found for " + month);
                return;
            }

            // Aggregate data
            double totalProfit = 0, totalRevenue = 0, totalCogs = 0;
            Map<String, Double> vendorProfits = new HashMap<>();
            Map<String, Double> distCosts = new HashMap<>();

            for (DailyReport report : reports) {
                if (report.isHasPurchaseData() && report.isHasSalesData()) {
                    totalProfit += report.getTotalNetProfit();
                    totalRevenue += report.getTotalGrossRevenue();
                    totalCogs += report.getTotalCogs();

                    report.getVendorReports().forEach(vr ->
                            vendorProfits.merge(vr.getVendorName(), vr.getNetProfit(), Double::sum)
                    );
                    report.getPurchaseReports().forEach(pr ->
                            distCosts.merge(pr.getDistributorName(), pr.getTotalCostPayable(), Double::sum)
                    );
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📈 **Monthly Report: ").append(month).append("** 📈\n\n");
            sb.append("**Overall P&L (from completed days):**\n");
            sb.append(String.format("- Total Net Profit: %.2f INR\n", totalProfit));
            sb.append(String.format("- Total Gross Revenue: %.2f INR\n", totalRevenue));
            sb.append(String.format("- Total COGS: %.2f INR\n\n", totalCogs));

            sb.append("**Top Vendors (by Profit):**\n");
            vendorProfits.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .forEach(entry -> sb.append(String.format("- %s: %.2f INR\n", entry.getKey(), reportService.round(entry.getValue()))));

            sb.append("\n**Total Distributor Costs:**\n");
            distCosts.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .forEach(entry -> sb.append(String.format("- %s: %.2f INR\n", entry.getKey(), reportService.round(entry.getValue()))));

            sendText(chatId, sb.toString());

        } catch (Exception e) {
            sendText(chatId, "Error generating report. Is the format 'YYYY-MM'? (e.g., 2025-10)");
        }
    }

    // --- Helper Methods ---
    private void sendText(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown") // Enable bold, italics
                .build();
        executeMessage(message);
    }

    private void executeMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message: {}", e.getMessage());
        }
    }
}
