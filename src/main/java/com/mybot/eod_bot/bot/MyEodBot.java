package com.mybot.eod_bot.bot;

import com.mybot.eod_bot.model.*;
import com.mybot.eod_bot.repository.*;
import com.mybot.eod_bot.service.*; // <-- UPDATED
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
// NO LONGER NEEDED: import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MyEodBot extends TelegramLongPollingBot {

    private final DistributorRepository distributorRepository;
    private final VendorRepository vendorRepository;
    private final LapuSimRepository lapuSimRepository;
    private final DailyReportRepository dailyReportRepository;
    private final ConversationService conversationService;
    private final ReportService reportService;
    private final ExcelParserService excelParserService;
    private final CsvReportService csvReportService;
    private final BotOperationsService botOperationsService; // <-- NEW SERVICE

    @Value("${telegram.bot.username}")
    private String botUsername;

    // --- UPDATED CONSTRUCTOR ---
    public MyEodBot(@Value("${telegram.bot.token}") String botToken,
                    DistributorRepository distributorRepository,
                    VendorRepository vendorRepository,
                    LapuSimRepository lapuSimRepository,
                    DailyReportRepository dailyReportRepository,
                    ConversationService conversationService,
                    ReportService reportService,
                    ExcelParserService excelParserService,
                    CsvReportService csvReportService,
                    BotOperationsService botOperationsService) { // <-- NEW SERVICE
        super(botToken);
        this.distributorRepository = distributorRepository;
        this.vendorRepository = vendorRepository;
        this.lapuSimRepository = lapuSimRepository;
        this.dailyReportRepository = dailyReportRepository;
        this.conversationService = conversationService;
        this.reportService = reportService;
        this.excelParserService = excelParserService;
        this.csvReportService = csvReportService;
        this.botOperationsService = botOperationsService; // <-- NEW SERVICE
    }

    @PostConstruct
    public void registerBot() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("===================================================================");
            log.info(">>> BOT IS ONLINE AND REGISTERED SUCCESSFULLY: @{} <<<", getBotUsername());
            log.info("===================================================================");
        } catch (TelegramApiException e) {
            log.error("!!!!!!!! FAILED TO REGISTER BOT !!!!!!!!", e);
        }
    }


    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public void onUpdateReceived(Update update) {

        // --- HARDENED /cancel check ---
        if (update.hasMessage() && update.getMessage().hasText() && "/cancel".equalsIgnoreCase(update.getMessage().getText().trim())) {
            long chatId = update.getMessage().getChatId();
            conversationService.clearState(chatId);
            sendText(chatId, "Operation cancelled.");
            return; // Stop processing
        }

        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                long chatId = update.getMessage().getChatId();
                String text = update.getMessage().getText();
                String state = conversationService.getState(chatId);

                log.info("Received message: '{}' from chat ID: {} with state: {}", text, chatId, state);

                if (state != null) handleConversation(chatId, text, state);
                else if (text.startsWith("/")) handleCommand(chatId, text);

            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery().getMessage().getChatId(), update.getCallbackQuery().getData(), update.getCallbackQuery().getMessage().getMessageId());

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
        String[] parts = command.split(" ");
        String cmd = parts[0];

        switch (cmd) {
            case "/start":
                sendText(chatId, "Welcome! I am your EOD Bot. Commands:\n\n" +
                        "<b>Setup (Distributors):</b>\n" +
                        "  <code>/add_distributor</code>\n" +
                        "  <code>/list_distributors</code>\n" +
                        "  <code>/delete_distributor</code>\n\n" +
                        "<b>Setup (Vendors):</b>\n" +
                        "  <code>/add_vendor</code>\n" +
                        "  <code>/list_vendors</code>\n" +
                        "  <code>/delete_vendor</code>\n\n" +
                        "<b>Setup (SIMs):</b>\n" +
                        "  <code>/assign_sim</code>\n" +
                        "  <code>/unassign_sim</code>\n" +
                        "  <code>/list_sims</code> (e.g. /list_sims Jio Rakesh)\n\n" +
                        "<b>Daily Use:</b>\n" +
                        "  <code>/run_purchases</code> - Upload .xls file\n" +
                        "  <code>/run_sales</code> - Enter vendor sales\n\n" +
                        "<b>Reporting:</b>\n" +
                        "  <code>/get_daily_report</code> (e.g. /get_daily_report 2025-10-30)\n" +
                        "  <code>/get_monthly_report</code> (e.g. /get_monthly_report 2025-10)\n\n" +
                        "<b>Other:</b>\n" +
                        "  <code>/cancel</code> - Cancel current operation");
                break;
            // --- Distributor Commands ---
            case "/add_distributor":
                conversationService.setTempData(chatId, new Distributor());
                conversationService.setState(chatId, "AWAIT_DIST_NAME");
                sendText(chatId, "What is the Distributor's Name?");
                break;
            case "/list_distributors":
                listDistributors(chatId);
                break;
            case "/delete_distributor":
                sendDistributorList(chatId, "delete_dist_");
                break;

            // --- Vendor Commands ---
            case "/add_vendor":
                conversationService.setTempData(chatId, new Vendor());
                conversationService.setState(chatId, "AWAIT_VENDOR_NAME");
                sendText(chatId, "What is the Vendor's Name?");
                break;
            case "/list_vendors":
                listVendors(chatId);
                break;
            case "/delete_vendor":
                sendVendorList(chatId, "delete_vendor_");
                break;

            // --- SIM Commands ---
            case "/assign_sim":
                sendDistributorList(chatId, "assign_sim_dist_");
                break;
            case "/unassign_sim":
                conversationService.setState(chatId, "AWAIT_SIM_FOR_UNASSIGN");
                sendText(chatId, "Please enter the LAPU SIM Number to unassign:");
                break;
            case "/list_sims":
                listSimsForDistributor(chatId, command);
                break;

            // --- Daily Use ---
            case "/run_purchases":
                conversationService.setState(chatId, "AWAIT_STAT_FILE");
                sendText(chatId, "Please upload your <code>.xls</code> file from mrobotics.");
                break;
            case "/run_sales":
                startSalesFlow(chatId);
                break;

            // --- Reporting ---
            case "/get_daily_report":
                getDailyReport(chatId, parts);
                break;
            case "/get_monthly_report":
                getMonthlyReport(chatId, parts);
                break;

            default:
                sendText(chatId, "Unknown command. Try /start");
        }
    }


    // --- 2. State-Based Conversation Handling ---
    private void handleConversation(long chatId, String text, String state) {
        try {
            switch (state) {
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
                    // --- UPDATED: Use the service ---
                    botOperationsService.saveNewDistributor(dist);
                    conversationService.clearState(chatId);
                    sendText(chatId, "✅ Distributor '<b>" + dist.getName() + "</b>' saved!");
                    break;

                case "AWAIT_VENDOR_NAME":
                    Vendor vendor = conversationService.getTempData(chatId, Vendor.class);
                    vendor.setName(text);
                    conversationService.setState(chatId, "AWAIT_VENDOR_DISCOUNT");
                    sendText(chatId, "What is their discount percent? (e.g., 1.5)");
                    break;
                case "AWAIT_VENDOR_DISCOUNT":
                    vendor = conversationService.getTempData(chatId, Vendor.class);
                    vendor.setDiscountPercent(Double.parseDouble(text));
                    // --- UPDATED: Use the service ---
                    botOperationsService.saveNewVendor(vendor);
                    conversationService.clearState(chatId);
                    sendText(chatId, "✅ Vendor '<b>" + vendor.getName() + "</b>' saved!");
                    break;

                // --- UPDATED: BATCH SIM ASSIGNMENT ---
                case "AWAIT_BULK_SIM_NO":
                    Long distId = conversationService.getTempData(chatId, Long.class);
                    Distributor assignedDist = distributorRepository.findById(distId)
                            .orElseThrow(() -> new RuntimeException("Distributor not found"));

                    // Split by space, comma, semicolon, or newline
                    String[] simNumbers = text.split("[\\s,;\\n]+");

                    // --- UPDATED: Use the service ---
                    List<LapuSim> savedSims = botOperationsService.saveBatchSims(assignedDist, simNumbers);

                    conversationService.clearState(chatId);
                    sendText(chatId, "✅ <b>" + savedSims.size() + "</b> SIM(s) are now assigned to '<b>" + assignedDist.getName() + "</b>'.");
                    break;

                case "AWAIT_SIM_FOR_UNASSIGN":
                    // --- UPDATED: Use the service ---
                    botOperationsService.unassignSim(text.trim());
                    sendText(chatId, "✅ SIM <code>" + text.trim() + "</code> has been unassigned.");
                    conversationService.clearState(chatId);
                    break;

                case "AWAIT_DIST_DELETE_CONFIRM":
                    // --- UPDATED: Use the service ---
                    if ("YES".equals(text.trim())) {
                        Distributor distToDel = conversationService.getTempData(chatId, Distributor.class);
                        botOperationsService.confirmDeleteDistributor(distToDel);
                        sendText(chatId, "✅ Distributor '<b>" + distToDel.getName() + "</b>' and all associated SIMs have been deleted.");
                    } else {
                        sendText(chatId, "Deletion cancelled.");
                    }
                    conversationService.clearState(chatId);
                    break;
                case "AWAIT_VENDOR_DELETE_CONFIRM":
                    // --- UPDATED: Use the service ---
                    if ("YES".equals(text.trim())) {
                        Vendor vendorToDel = conversationService.getTempData(chatId, Vendor.class);
                        botOperationsService.confirmDeleteVendor(vendorToDel);
                        sendText(chatId, "✅ Vendor '<b>" + vendorToDel.getName() + "</b>' has been deleted.");
                    } else {
                        sendText(chatId, "Deletion cancelled.");
                    }
                    conversationService.clearState(chatId);
                    break;

                // Sales entry flow
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
                    processVendorSale(chatId);
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
    private void handleCallbackQuery(long chatId, String data, int messageId) {
        // --- UPDATED: BATCH SIM ASSIGNMENT ---
        if (data.startsWith("assign_sim_dist_")) {
            Long distId = Long.parseLong(data.substring("assign_sim_dist_".length()));
            conversationService.setTempData(chatId, distId);
            conversationService.setState(chatId, "AWAIT_BULK_SIM_NO"); // Changed from AWAIT_SIM_NO
            sendText(chatId, "Please enter all LAPU SIM Numbers (<code>Lapu No</code>).\n" +
                    "You can paste a list separated by spaces, commas, or new lines.");
        }
        else if (data.startsWith("delete_dist_")) {
            Long distId = Long.parseLong(data.substring("delete_dist_".length()));
            Distributor dist = distributorRepository.findById(distId).orElse(null);
            if (dist == null) {
                sendText(chatId, "Error: Distributor not found.");
                return;
            }
            conversationService.setTempData(chatId, dist);
            conversationService.setState(chatId, "AWAIT_DIST_DELETE_CONFIRM");
            sendText(chatId, "⚠️ Are you sure you want to delete distributor '<b>" + dist.getName() + "</b>'?\n" +
                    "This will also delete all SIMs assigned to them.\n" +
                    "Type <code>YES</code> to confirm.");
        }
        else if (data.startsWith("delete_vendor_")) {
            Long vendorId = Long.parseLong(data.substring("delete_vendor_".length()));
            Vendor vendor = vendorRepository.findById(vendorId).orElse(null);
            if (vendor == null) {
                sendText(chatId, "Error: Vendor not found.");
                return;
            }
            conversationService.setTempData(chatId, vendor);
            conversationService.setState(chatId, "AWAIT_VENDOR_DELETE_CONFIRM");
            sendText(chatId, "⚠️ Are you sure you want to delete vendor '<b>" + vendor.getName() + "</b>'?\n" +
                    "Type <code>YES</code> to confirm.");
        }
    }

    // --- 4. Document Upload Handling (Excel) ---
    private void handleDocument(long chatId, Document document) {
        String state = conversationService.getState(chatId);
        if (!"AWAIT_STAT_FILE".equals(state)) {
            sendText(chatId, "I wasn't expecting a file. Please start a command like <code>/run_purchases</code> first.");
            return;
        }

        String fileName = document.getFileName();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xls"))) { // Only allow .xls
            sendText(chatId, "Invalid file type. Please upload an <code>.xls</code> file.");
            return;
        }

        sendText(chatId, "File received! Parsing Excel file (<code>.xls</code>)...");
        File localFile = null;
        try {
            localFile = downloadTelegramFile(document.getFileId());

            Map<String, LapuSim> simMap = lapuSimRepository.findAll().stream()
                    .collect(Collectors.toMap(LapuSim::getLapuNo, sim -> sim));

            if (simMap.isEmpty()) {
                sendText(chatId, "Error: No SIMs found in database. Please <code>/assign_sim</code> first.");
                conversationService.clearState(chatId);
                return;
            }

            ExcelParserService.PurchaseParseResult parseResult = excelParserService.parse(localFile, simMap);

            DailyReport dailyReport = reportService.getOrCreateDailyReport(LocalDate.now());
            String pnlStatus = reportService.updatePurchases(
                    dailyReport,
                    new ArrayList<>(parseResult.purchaseReportsMap.values()),
                    parseResult.masterCostFactor
            );

            // Per-distributor breakdown
            StringBuilder sb = new StringBuilder();
            sb.append("✅ <b>Purchases Logged:</b>\n\n");

            sb.append("<b>Purchase Summary by Distributor:</b>\n");
            for (DailyPurchaseReport pr : parseResult.purchaseReportsMap.values()) {
                sb.append(String.format("- <b>%s</b>:\n", pr.getDistributorName()));
                sb.append(String.format("  - Load: %.2f INR\n", pr.getTotalLoadReceived()));
                sb.append(String.format("  - Cost: %.2f INR\n", pr.getTotalCostPayable()));
            }

            sb.append("\n<b>Overall Totals:</b>\n");
            sb.append(String.format("- Total Load: %.2f INR\n", parseResult.totalLoadReceived));
            sb.append(String.format("- Total Cost: %.2f INR\n", parseResult.totalCostPayable));
            sb.append(String.format("- Daily Cost Factor: %.6f\n\n", parseResult.masterCostFactor));

            if (!parseResult.unassignedSims.isEmpty()) {
                sb.append("<b>(!) Unassigned SIMs found:</b>\n");
                parseResult.unassignedSims.stream().distinct().limit(10).forEach(s -> sb.append("- <code>").append(s).append("</code>\n"));
                if(parseResult.unassignedSims.size() > 10) sb.append("...and more.\n");
                sb.append("<i>Use /assign_sim to fix.</i>\n\n");
            }

            sb.append(pnlStatus);
            sendText(chatId, sb.toString());
            conversationService.clearState(chatId);

        } catch (Exception e) {
            log.error("File processing failed: {}", e.getMessage(), e);
            sendText(chatId, "Error processing file: " + e.getMessage());
            conversationService.clearState(chatId);
        } finally {
            if (localFile != null) {
                localFile.delete();
            }
        }
    }

    // --- 5. P&L Sales Entry Flow ---
    private void startSalesFlow(long chatId) {
        DailyReport dailyReport = reportService.getOrCreateDailyReport(LocalDate.now());
        if (!dailyReport.isHasPurchaseData()) {
            sendText(chatId, "Warning: You haven't run <code>/run_purchases</code> yet.\n" +
                    "I can't calculate your <b>true</b> profit until I know today's cost factor.\n" +
                    "You can continue, but P&L will only be calculated after you run purchases.");
        }

        List<Vendor> vendors = vendorRepository.findAll();
        if (vendors.isEmpty()) {
            sendText(chatId, "No vendors found. Please <code>/add_vendor</code> first.");
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
        sendText(chatId, "--- Processing Vendor: <b>" + vendor.getName() + " (" + vendor.getDiscountPercent() + "%)</b> ---\n" +
                "1. Enter YESTERDAY'S End Balance:");
    }

    private void processVendorSale(long chatId) {
        Map<String, Object> reportData = conversationService.getTempData(chatId, Map.class);
        DailyReport dailyReport = reportService.getOrCreateDailyReport(LocalDate.now());

        Vendor vendor = (Vendor) reportData.get("currentVendor");
        double prevBal = (Double) reportData.get("current_prev_bal");
        double topup = (Double) reportData.get("current_topup");
        double endBal = (Double) reportData.get("current_end_bal");
        double costFactor = dailyReport.getMasterCostFactor();
        List<DailyVendorReport> vendorReports = (List<DailyVendorReport>) reportData.get("vendorReports");

        double grossRevenue = (prevBal + topup) - endBal;
        double discountFactor = 1 - (vendor.getDiscountPercent() / 100);
        double totalLoadSold = (discountFactor == 0) ? 0 : (grossRevenue / discountFactor);
        double cogs = totalLoadSold * costFactor;
        double netProfit = grossRevenue - cogs;

        DailyVendorReport vr = new DailyVendorReport();
        vr.setVendorName(vendor.getName());
        vr.setGrossRevenue(reportService.round(grossRevenue));
        vr.setTotalLoadSold(reportService.round(totalLoadSold));
        vr.setCogs(reportService.round(cogs));
        vr.setNetProfit(reportService.round(netProfit));
        vendorReports.add(vr);

        sendText(chatId, String.format("✅ %s Logged. (Profit: %.2f)", vendor.getName(), vr.getNetProfit()));

        int index = (Integer) reportData.get("vendorIndex") + 1;
        List<Vendor> vendors = (List<Vendor>) reportData.get("vendors");

        if (index < vendors.size()) {
            reportData.put("vendorIndex", index);
            askForVendorBalance(chatId, vendors.get(index));
        } else {
            String pnlStatus = reportService.updateSales(dailyReport, vendorReports);
            sendText(chatId, "✅ <b>All vendor sales logged!</b>\n" + pnlStatus);
            conversationService.clearState(chatId);
        }
    }

    // --- 6. NEW/UPDATED Helper & CRUD Methods ---

    private void listDistributors(long chatId) {
        List<Distributor> distributors = distributorRepository.findAll();
        if (distributors.isEmpty()) {
            sendText(chatId, "No distributors set up. Use <code>/add_distributor</code>.");
            return;
        }
        StringBuilder sb = new StringBuilder("<b>Current Distributors:</b>\n");
        for (Distributor dist : distributors) {
            sb.append(String.format("- <b>%s</b> (Deal: Get %.2f, Pay %.2f)\n",
                    dist.getName(), dist.getBaseGet(), dist.getBasePay()));
        }
        sendText(chatId, sb.toString());
    }

    private void listVendors(long chatId) {
        List<Vendor> vendors = vendorRepository.findAll();
        if (vendors.isEmpty()) {
            sendText(chatId, "No vendors set up. Use <code>/add_vendor</code>.");
            return;
        }
        StringBuilder sb = new StringBuilder("<b>Current Vendors:</b>\n");
        for (Vendor vendor : vendors) {
            sb.append(String.format("- <b>%s</b> (Discount: %.2f%%)\n",
                    vendor.getName(), vendor.getDiscountPercent()));
        }
        sendText(chatId, sb.toString());
    }


    private void listSimsForDistributor(long chatId, String command) {
        String distName = command.substring("/list_sims".length()).trim();
        if (distName.isEmpty()) {
            sendText(chatId, "Please provide a distributor name. Usage: <code>/list_sims Jio Rakesh</code>");
            return;
        }
        Distributor dist = distributorRepository.findAll().stream()
                .filter(d -> d.getName().equalsIgnoreCase(distName))
                .findFirst().orElse(null);

        if (dist == null) {
            sendText(chatId, "Distributor '<b>" + distName + "</b>' not found.");
            return;
        }

        List<LapuSim> sims = lapuSimRepository.findByDistributor(dist);
        if (sims.isEmpty()) {
            sendText(chatId, "No SIMs assigned to '<b>" + dist.getName() + "</b>'.");
            return;
        }

        StringBuilder sb = new StringBuilder("<b>SIMs for " + dist.getName() + ":</b>\n");
        for (LapuSim sim : sims) {
            sb.append("- <code>").append(sim.getLapuNo()).append("</code>\n");
        }
        sendText(chatId, sb.toString());
    }

    // --- REMOVED Transactional logic from here. It's now in the service. ---

    private void sendDistributorList(long chatId, String callbackPrefix) {
        List<Distributor> distributors = distributorRepository.findAll();
        if (distributors.isEmpty()) {
            sendText(chatId, "No distributors found. Please <code>/add_distributor</code> first.");
            return;
        }
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder kb = InlineKeyboardMarkup.builder();
        for (Distributor dist : distributors) {
            kb.keyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(dist.getName())
                            .callbackData(callbackPrefix + dist.getId())
                            .build()
            ));
        }
        sendReplyMarkup(chatId, "Please select a distributor:", kb.build());
    }

    private void sendVendorList(long chatId, String callbackPrefix) {
        List<Vendor> vendors = vendorRepository.findAll();
        if (vendors.isEmpty()) {
            sendText(chatId, "No vendors found. Please <code>/add_vendor</code> first.");
            return;
        }
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder kb = InlineKeyboardMarkup.builder();
        for (Vendor v : vendors) {
            kb.keyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(v.getName())
                            .callbackData(callbackPrefix + v.getId())
                            .build()
            ));
        }
        sendReplyMarkup(chatId, "Please select a vendor:", kb.build());
    }


    // --- 7. NEW Advanced Reporting Methods ---

    private void getDailyReport(long chatId, String[] parts) {
        LocalDate date;
        try {
            if (parts.length > 1) {
                date = LocalDate.parse(parts[1]); // e.g., /get_daily_report 2025-10-30
            } else {
                date = LocalDate.now(); // default to today
            }
        } catch (DateTimeParseException e) {
            sendText(chatId, "Invalid date format. Please use YYYY-MM-DD.");
            return;
        }

        DailyReport report = reportService.getOrCreateDailyReport(date);
        StringBuilder sb = new StringBuilder();
        sb.append("📈 <b>Daily Report for ").append(date).append("</b> 📈\n\n");

        if (!report.isHasPurchaseData() && !report.isHasSalesData()) {
            sb.append("No data found for this day.");
            sendText(chatId, sb.toString());
            return;
        }

        // --- P&L Summary ---
        if (report.isHasPurchaseData() && report.isHasSalesData()) {
            sb.append("<b>P&L Summary:</b>\n");
            sb.append(String.format("- Gross Revenue: %.2f INR\n", report.getTotalGrossRevenue()));
            sb.append(String.format("- Cost of Sales: %.2f INR\n", report.getTotalCogs()));
            sb.append(String.format("- <b>NET PROFIT: %.2f INR</b>\n\n", report.getTotalNetProfit()));
        } else {
            sb.append("<b>P&L is pending.</b> Data is incomplete.\n\n");
        }

        // --- Vendor Profit Summary ---
        if (report.isHasSalesData()) {
            sb.append("<b>Vendor Profits:</b>\n");
            report.getVendorReports().forEach(vr ->
                    sb.append(String.format("- %s: %.2f INR\n", vr.getVendorName(), vr.getNetProfit()))
            );
            sb.append("\n");
        }

        // --- Distributor Cost Summary ---
        if (report.isHasPurchaseData()) {
            sb.append("<b>Distributor Costs:</b>\n");
            report.getPurchaseReports().forEach(pr ->
                    sb.append(String.format("- %s: %.2f INR\n", pr.getDistributorName(), pr.getTotalCostPayable()))
            );
        }

        sendText(chatId, sb.toString());

        // --- Send CSV File ---
        try {
            File csvFile = csvReportService.generateDailyReportCsv(report);
            sendDocument(chatId, new InputFile(csvFile), "daily_report_" + date + ".csv");
        } catch (IOException e) {
            log.error("Failed to generate daily CSV report: {}", e.getMessage(), e);
            sendText(chatId, "Could not generate CSV file.");
        }
    }

    private void getMonthlyReport(long chatId, String[] parts) {
        String month;
        try {
            if (parts.length > 1) {
                month = parts[1]; // e.g., 2025-10
                // Validate format
                YearMonth.parse(month);
            } else {
                month = YearMonth.now().toString(); // default to current month
            }
        } catch (DateTimeParseException e) {
            sendText(chatId, "Invalid month format. Please use YYYY-MM (e.g., 2025-10).");
            return;
        }

        LocalDate startDate = LocalDate.parse(month + "-01");
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);

        List<DailyReport> reports = dailyReportRepository.findByDateBetween(startDate, endDate);

        if (reports.isEmpty()) {
            sendText(chatId, "No data found for " + month);
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

        // Build and send chat summary
        StringBuilder sb = new StringBuilder();
        sb.append("📈 <b>Monthly Report: ").append(month).append("</b> 📈\n\n");
        sb.append("<b>Overall P&L (from completed days):</b>\n");
        sb.append(String.format("- Total Net Profit: %.2f INR\n", totalProfit));
        sb.append(String.format("- Total Gross Revenue: %.2f INR\n", totalRevenue));
        sb.append(String.format("- Total COGS: %.2f INR\n\n", totalCogs));

        sb.append("<b>Top Vendors (by Profit):</b>\n");
        vendorProfits.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> sb.append(String.format("- %s: %.2f INR\n", entry.getKey(), reportService.round(entry.getValue()))));

        sb.append("\n<b>Total Distributor Costs:</b>\n");
        distCosts.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> sb.append(String.format("- %s: %.2f INR\n", entry.getKey(), reportService.round(entry.getValue()))));

        sendText(chatId, sb.toString());

        // --- Send CSV File ---
        try {
            File csvFile = csvReportService.generateMonthlyReportCsv(reports, month);
            sendDocument(chatId, new InputFile(csvFile), "monthly_report_" + month + ".csv");
        } catch (IOException e) {
            log.error("Failed to generate monthly CSV report: {}", e.getMessage(), e);
            sendText(chatId, "Could not generate CSV file.");
        }
    }


    // --- 8. Core Helper Methods ---

    private File downloadTelegramFile(String fileId) throws TelegramApiException, IOException {
        GetFile getFile = new GetFile(fileId);
        org.telegram.telegrambots.meta.api.objects.File telegramFile = execute(getFile);

        File localFile = File.createTempFile("telegram-", ".download");

        try (InputStream in = new URL(telegramFile.getFileUrl(getBotToken())).openStream()) {
            Files.copy(in, localFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return localFile;
    }

    private void sendText(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML") // <-- UPDATED TO HTML
                .build();
        executeMessage(message);
    }

    private void sendReplyMarkup(long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(markup)
                .build();
        executeMessage(message);
    }

    private void sendDocument(long chatId, InputFile file, String caption) {
        SendDocument document = SendDocument.builder()
                .chatId(chatId)
                .document(file)
                .caption(caption)
                .build();
        try {
            execute(document);
        } catch (TelegramApiException e) {
            log.error("Failed to send document: {}", e.getMessage(), e);
        }
    }

    private void executeMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message: {}", e.getMessage());
        }
    }
}

