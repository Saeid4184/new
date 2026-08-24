package ir.factory.entryexit.data

/**
 * The 4 kinds of write-up a guard can file from the new "گزارشات حراست" tab, each backed by a
 * short, fixed Persian sentence template with a couple of blanks — the blanks are filled from a
 * dropdown of the phrases the security team actually uses day to day (with a "سایر" escape hatch
 * that reveals a free-text field, so an unusual case is never blocked), rather than a fully
 * free-form report. Keeps write-ups short, consistent, and easy to scan/aggregate later.
 */
enum class ReportType(val label: String) {
    VIOLATION("گزارش تخلف"),
    INCIDENT("گزارش حادثه / نزدیک‌به‌حادثه"),
    POSITIVE("گزارش تقدیر"),
    GENERAL("گزارش عمومی")
}

object ReportCatalog {

    /** Free-text escape hatch offered at the end of every dropdown below — picking it reveals
     *  a text field instead of forcing an inexact fit into a fixed list. */
    const val OTHER = "سایر (تایپ کنید)"

    fun categoryOptionsFor(type: ReportType): List<String> = when (type) {
        ReportType.VIOLATION -> listOf(
            "عدم رعایت پوشش/تجهیزات ایمنی", "ورود بدون مجوز", "استعمال دخانیات در محل ممنوعه",
            "عدم همکاری با نگهبان", "تأخیر/غیبت در شیفت", "خواب در حین نگهبانی",
            "نقض پروتکل ورود و خروج", OTHER
        )
        ReportType.INCIDENT -> listOf(
            "آتش‌سوزی", "سرقت / مفقودی", "نشتی مواد", "برخورد وسیله نقلیه",
            "سقوط یا زمین‌خوردن", "نقص فنی تجهیزات", "قطعی برق/آب", OTHER
        )
        ReportType.POSITIVE -> listOf(
            "برخورد مناسب با مراجعین", "هوشیاری و پیشگیری از حادثه", "پیگیری وظایف فراتر از انتظار",
            "کمک به همکار", "گزارش‌دهی دقیق و به‌موقع", OTHER
        )
        ReportType.GENERAL -> listOf(
            "اطلاع‌رسانی داخلی", "درخواست پیگیری", "یادداشت انتقال شیفت", "سایر موارد", OTHER
        )
    }

    /** Only meaningful for VIOLATION/INCIDENT — [severityOptions] is shared between them. */
    val severityOptions = listOf("خفیف", "متوسط", "شدید")

    fun showsSeverity(type: ReportType): Boolean = type == ReportType.VIOLATION || type == ReportType.INCIDENT

    val violationActionOptions = listOf(
        "تذکر شفاهی", "تذکر کتبی", "گزارش به مدیریت", "ارجاع به انتظامات", OTHER
    )

    fun showsAction(type: ReportType): Boolean = type == ReportType.VIOLATION

    fun showsSubjectName(type: ReportType): Boolean = type == ReportType.VIOLATION || type == ReportType.POSITIVE

    /** The Mad-libs style preview sentence shown live on the form as the guard fills things
     *  in, and stored as [ReportEntity.summaryText] for the list screen. */
    fun buildSummary(
        type: ReportType,
        dateText: String,
        category: String,
        severity: String?,
        subjectName: String?,
        location: String?,
        actionTaken: String?,
        description: String
    ): String {
        val desc = description.trim().ifEmpty { "—" }
        return when (type) {
            ReportType.VIOLATION -> buildString {
                append("در تاریخ $dateText")
                if (!subjectName.isNullOrBlank()) append("، آقای/خانم «$subjectName»")
                append(" به دلیل «${category.ifBlank { "—" }}»")
                if (!severity.isNullOrBlank()) append(" (درجه: $severity)")
                append(" مورد گزارش تخلف قرار گرفت.")
                if (!actionTaken.isNullOrBlank()) append(" اقدام انجام‌شده: $actionTaken.")
                append(" توضیحات: $desc")
            }
            ReportType.INCIDENT -> buildString {
                append("در تاریخ $dateText یک مورد «${category.ifBlank { "—" }}»")
                if (!location.isNullOrBlank()) append(" در «$location»")
                if (!severity.isNullOrBlank()) append(" با شدت «$severity»")
                append(" گزارش شد.")
                append(" توضیحات: $desc")
            }
            ReportType.POSITIVE -> buildString {
                append("در تاریخ $dateText")
                if (!subjectName.isNullOrBlank()) append("، از آقای/خانم «$subjectName»")
                append(" به دلیل «${category.ifBlank { "—" }}» تقدیر به عمل آمد.")
                append(" توضیحات: $desc")
            }
            ReportType.GENERAL -> buildString {
                append("در تاریخ $dateText گزارش عمومی با موضوع «${category.ifBlank { "—" }}» ثبت شد.")
                if (!location.isNullOrBlank()) append(" محل: $location.")
                append(" توضیحات: $desc")
            }
        }
    }
}
