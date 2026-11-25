package uk.co.jwgardenservices.crm.voice

/**
 * The fixed set of commands the assistant can act on.
 *
 * The on-device Qwen model was removed in the audit: it was never on the
 * runtime path (the rules answer common commands and the optional cloud
 * classifier handles the rest), yet it cost a 469 MB download and the
 * llama.cpp dependency. These types remain so the rules, the cloud JSON
 * classifier and the action runner share one vocabulary.
 */
enum class SpokenIntent(val wire: String) {
    LogHours("log_hours"),
    CheckSchedule("check_schedule"),
    NextJob("next_job"),
    NextJobAddress("next_job_address"),
    MarkJobDone("mark_job_done"),
    CheckDebtors("who_owes_me"),
    WeeklyEarnings("weekly_earnings"),
    MonthEarnings("month_earnings"),
    HoursThisWeek("hours_this_week"),
    ClientInfo("client_info"),
    LastVisit("last_visit"),
    BestClient("best_client"),
    AddNote("add_note"),
    Unbilled("unbilled"),
    ClientList("client_list"),
    InvoiceStatus("invoice_status"),
    InvoiceEdit("invoice_edit"),
    NextJobForClient("next_job_for_client"),
    TodayEarnings("today_earnings"),
    BusinessInfo("business_info"),
    RunningCosts("running_costs"),
    Profit("profit"),
    Health("health_score"),
    DayWrapUp("day_wrap_up"),
    RaiseInvoice("raise_invoice"),
    AdjustCharge("adjust_charge"),
    AddExpense("add_expense"),
    NextVisitNote("next_visit_note"),
    SayAgain("say_again"),
    Continue("continue"),
    Weather("weather"),
    Lookup("lookup"),
    Quote("quote"),
    Other("other"),
}

/** What one spoken sentence was understood to mean. */
data class IntentResult(
    val intent: SpokenIntent,
    val clientOrTopic: String,
    val requiresCloud: Boolean,
)
