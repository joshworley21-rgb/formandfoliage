package uk.co.jwgardenservices.crm.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import uk.co.jwgardenservices.crm.core.Branding
import uk.co.jwgardenservices.crm.core.TaxReservePrefs
import androidx.compose.material3.Switch
import java.time.LocalDate
import uk.co.jwgardenservices.crm.domain.formatDateTime
import uk.co.jwgardenservices.crm.domain.invoicesCsv
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.taxYearCsv
import uk.co.jwgardenservices.crm.domain.ukTaxYearContaining
import uk.co.jwgardenservices.crm.domain.ukTaxYearLabel
import uk.co.jwgardenservices.crm.domain.visitsCsv
import uk.co.jwgardenservices.crm.ui.common.shareCsv
import uk.co.jwgardenservices.crm.core.AppLock
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.TeamMember
import uk.co.jwgardenservices.crm.data.model.SettingsDraft
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.OptionPicker
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.ShareText
import uk.co.jwgardenservices.crm.ui.theme.LocalSetThemeMode
import uk.co.jwgardenservices.crm.ui.theme.LocalThemeMode
import uk.co.jwgardenservices.crm.ui.theme.ThemeMode

private data class SettingsForm(
    val businessName: String = "Form & Foliage",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val replyToEmail: String = "",
    val publicSiteUrl: String = "",
    val vatRegistered: Boolean = false,
    val vatNumber: String = "",
    val vatRate: String = "20",
    val invoicePrefix: String = "JW-",
    val paymentTerms: String = "Payment due within 14 days.",
    val bankDetails: String = "",
    val quoteValidityDays: String = "30",
    val quoteTerms: String = "",
    val emailSubjectTemplate: String = "Invoice {{invoice_number}} from {{business_name}}",
    val emailBodyTemplate: String =
        "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}",
)

private fun formOf(settings: Settings?): SettingsForm = if (settings == null) {
    SettingsForm()
} else {
    SettingsForm(
        businessName = settings.businessName,
        address = settings.address.orEmpty(),
        phone = settings.phone.orEmpty(),
        email = settings.email.orEmpty(),
        replyToEmail = settings.replyToEmail.orEmpty(),
        publicSiteUrl = settings.publicSiteUrl.orEmpty(),
        vatRegistered = settings.vatRegistered,
        vatNumber = settings.vatNumber.orEmpty(),
        vatRate = settings.vatRate.toString(),
        invoicePrefix = settings.invoicePrefix,
        paymentTerms = settings.paymentTerms.orEmpty(),
        bankDetails = settings.bankDetails.orEmpty(),
        quoteValidityDays = settings.quoteValidityDays.toString(),
        quoteTerms = settings.quoteTerms.orEmpty(),
        emailSubjectTemplate = settings.emailSubjectTemplate,
        emailBodyTemplate = settings.emailBodyTemplate,
    )
}

@Composable
fun SettingsScreen(
    viewModel: CrmViewModel,
    onOpenAssistantSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var form by remember { mutableStateOf(SettingsForm()) }
    var initialised by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var inviteEmail by remember { mutableStateOf("") }
    var inviteName by remember { mutableStateOf("") }
    var inviteError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<TeamMember?>(null) }

    var knowledgeOpen by remember { mutableStateOf(false) }
    var knowledgeBusy by remember { mutableStateOf(false) }
    var knowledgeOnPhoneBusy by remember { mutableStateOf(false) }
    var knowledgeError by remember { mutableStateOf<String?>(null) }
    var knowledgeNote by remember { mutableStateOf<String?>(null) }

    var appLockOn by remember { mutableStateOf(AppLock.isEnabled(context)) }
    val taxRate by TaxReservePrefs.ratePercent.collectAsStateWithLifecycle()
    val themeMode = LocalThemeMode.current
    val setThemeMode = LocalSetThemeMode.current

    LaunchedEffect(state.settings) {
        if (!initialised && state.settings != null) {
            form = formOf(state.settings)
            initialised = true
        }
    }

    fun invite() {
        scope.launch {
            inviteError = null
            try {
                viewModel.inviteMember(inviteEmail, inviteName.ifBlank { null })
                inviteEmail = ""
                inviteName = ""
                Toast.makeText(context, "Employee invited", Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                inviteError = failure.message ?: "Could not invite"
            }
        }
    }

    fun remove(member: TeamMember) {
        scope.launch {
            try {
                viewModel.removeMember(member.id)
                removing = null
                Toast.makeText(context, "Access removed", Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                Toast.makeText(
                    context,
                    failure.message ?: "Could not remove access",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun learnFromWebsite() {
        val url = form.publicSiteUrl.trim()
        if (url.isBlank()) {
            knowledgeError = "Add the website address above first."
            return
        }
        scope.launch {
            knowledgeBusy = true
            knowledgeError = null
            knowledgeNote = null
            try {
                val result = viewModel.learnBusinessProfile(url)
                knowledgeNote = "Learned from ${result.pages.size} " +
                    (if (result.pages.size == 1) "page with AI." else "pages with AI.")
                Toast.makeText(context, "Secretary has read the website", Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                // No key, no signal for the AI route, or the model refused:
                // fall back to the free scan rather than leaving a dead end.
                try {
                    val local = viewModel.learnBusinessProfileLocally(url)
                    knowledgeNote = "AI was unavailable — used the free on-phone scan " +
                        "(${local.pages.size} ${if (local.pages.size == 1) "page" else "pages"})."
                } catch (localFailure: Exception) {
                    knowledgeError = localFailure.message
                        ?: failure.message
                        ?: "Could not read that website"
                }
            } finally {
                knowledgeBusy = false
            }
        }
    }

    fun learnOnPhone() {
        val url = form.publicSiteUrl.trim()
        if (url.isBlank()) {
            knowledgeError = "Add the website address above first."
            return
        }
        scope.launch {
            knowledgeOnPhoneBusy = true
            knowledgeError = null
            knowledgeNote = null
            try {
                val result = viewModel.learnBusinessProfileLocally(url)
                knowledgeNote = "Read ${result.pages.size} " +
                    (if (result.pages.size == 1) "page on this phone." else "pages on this phone.")
                Toast.makeText(context, "Website read on this phone", Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                knowledgeError = failure.message ?: "Could not read that website"
            } finally {
                knowledgeOnPhoneBusy = false
            }
        }
    }

    fun save() {
        scope.launch {
            error = null
            try {
                viewModel.saveSettings(
                    SettingsDraft(
                        businessName = form.businessName,
                        address = form.address,
                        phone = form.phone,
                        email = form.email,
                        replyToEmail = form.replyToEmail,
                        publicSiteUrl = form.publicSiteUrl,
                        vatRegistered = form.vatRegistered,
                        vatNumber = form.vatNumber,
                        vatRate = form.vatRate.toDoubleOrNull() ?: 0.0,
                        invoicePrefix = form.invoicePrefix,
                        paymentTerms = form.paymentTerms,
                        bankDetails = form.bankDetails,
                        quoteValidityDays = form.quoteValidityDays.toIntOrNull() ?: 30,
                        quoteTerms = form.quoteTerms,
                        emailSubjectTemplate = form.emailSubjectTemplate,
                        emailBodyTemplate = form.emailBodyTemplate,
                    ),
                )
                Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                error = failure.message ?: "Could not save settings"
            }
        }
    }

    if (state.loading) {
        Column(Modifier.padding(20.dp)) {
            PageHeader("Settings", "The details that appear on every invoice and quote.")
            LoadingState()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageHeader(
                title = "Settings",
                description = "The details that appear on every invoice and quote.",
                action = {
                    Button(onClick = ::save, enabled = !state.saving) {
                        Text(if (state.saving) "Saving…" else "Save")
                    }
                },
            )
        }

        if (error != null) {
            item {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            SectionCard(title = "Appearance") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    OptionPicker(
                        options = ThemeMode.entries,
                        selected = themeMode,
                        label = { it?.label ?: "Follow system" },
                        enabled = true,
                        onSelect = setThemeMode,
                    )
                    Text(
                        text = "Follow system uses the phone's light or dark setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Secretary") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Choose the voice Secretary uses and check which local models are installed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenAssistantSettings) {
                        Text("Voice & models")
                    }
                }
            }
        }

        item {
            CalendarSyncSection()
        }

        item {
            SectionCard(title = "Business details") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The app is Form & Foliage; the paperwork carries the
                    // business that is signed in.
                    var logoVersion by remember { mutableIntStateOf(0) }
                    val logoBitmap = remember(logoVersion) {
                        Branding.load(context)?.asImageBitmap()
                    }
                    val logoPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) { uri ->
                        if (uri != null) {
                            scope.launch {
                                if (Branding.save(context, uri)) {
                                    logoVersion++
                                    Toast.makeText(context, "Logo saved", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "That image could not be read", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .width(96.dp)
                                .height(64.dp),
                        ) {
                            if (logoBitmap != null) {
                                Image(
                                    bitmap = logoBitmap,
                                    contentDescription = "Business logo",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "No logo",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { logoPicker.launch("image/*") }) {
                                Text("Choose logo")
                            }
                            if (logoBitmap != null) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            Branding.clear(context)
                                            logoVersion++
                                        }
                                    },
                                ) { Text("Remove") }
                            }
                        }
                    }
                    Text(
                        text = "Shown on invoices and in the invoice PDF. Each business " +
                            "keeps its own logo on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = form.businessName,
                        onValueChange = { form = form.copy(businessName = it) },
                        label = { Text("Business name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.address,
                        onValueChange = { form = form.copy(address = it) },
                        label = { Text("Address") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.phone,
                        onValueChange = { form = form.copy(phone = it) },
                        label = { Text("Phone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.email,
                        onValueChange = { form = form.copy(email = it) },
                        label = { Text("Email (sending address)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.replyToEmail,
                        onValueChange = { form = form.copy(replyToEmail = it) },
                        label = { Text("Reply-to email (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Leave reply-to blank to send replies to the address above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Website and enquiries") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = form.publicSiteUrl,
                        onValueChange = { form = form.copy(publicSiteUrl = it) },
                        label = { Text("Website address") },
                        placeholder = { Text("https://jwgardenservices.co.uk") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val site = form.publicSiteUrl.trim().trimEnd('/')
                    val key = state.settings?.enquiryKey
                    if (site.isNotBlank() && !key.isNullOrBlank()) {
                        val link = "$site/enquire/$key"
                        Text(
                            text = link,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                ShareText.share(context, link, "Share enquiry link")
                            },
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share enquiry link")
                        }
                    } else {
                        Text(
                            text = "Save a website address and the ready-made enquiry link will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Business knowledge") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Secretary uses this profile to answer questions about what you do, " +
                            "where you cover and how you talk. Learn it from your website, then " +
                            "correct anything that is wrong.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val profile = state.settings?.businessProfile
                    if (profile.isNullOrBlank()) {
                        Text(
                            text = "Not learned yet — Secretary is using the built-in profile.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = profile.take(420) + if (profile.length > 420) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val source = state.settings?.businessProfileSource
                        val updated = parseTimestamp(
                            state.settings?.businessProfileUpdatedAt,
                        )?.formatDateTime()
                        if (!source.isNullOrBlank() || updated != null) {
                            Text(
                                text = listOfNotNull(
                                    source?.takeIf { it.isNotBlank() }?.let { "From $it" },
                                    updated,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (knowledgeError != null) {
                        Text(
                            text = knowledgeError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (knowledgeNote != null) {
                        Text(
                            text = knowledgeNote.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = ::learnOnPhone,
                            enabled = !knowledgeBusy && !knowledgeOnPhoneBusy &&
                                form.publicSiteUrl.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (knowledgeOnPhoneBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (knowledgeOnPhoneBusy) "Reading…" else "Scan on phone")
                        }
                        OutlinedButton(
                            onClick = ::learnFromWebsite,
                            enabled = !knowledgeBusy && !knowledgeOnPhoneBusy &&
                                form.publicSiteUrl.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (knowledgeBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (knowledgeBusy) "Thinking…" else "Use AI")
                        }
                    }

                    TextButton(
                        onClick = { knowledgeOpen = true },
                        enabled = !knowledgeBusy && !knowledgeOnPhoneBusy,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Edit knowledge")
                    }

                    Text(
                        text = "On-phone is free and needs no API key; it keeps the services, " +
                            "areas, credentials and contact details it can read. Use AI for a " +
                            "better written profile — that one uses your OpenAI key. Both read " +
                            "the home page plus about, services, areas and contact pages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Team access") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Invite an employee by email. When they sign up with that address they can see the schedule, client names, addresses and notes, and mark jobs complete. They cannot see rates, visits, invoices or these settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        label = { Text("Work email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = inviteName,
                        onValueChange = { inviteName = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = ::invite,
                        enabled = inviteEmail.isNotBlank() && !state.saving,
                    ) {
                        Text("Invite")
                    }
                    if (inviteError != null) {
                        Text(
                            text = inviteError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    HorizontalDivider()

                    if (state.team.isEmpty()) {
                        Text(
                            text = "No employees yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.team.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = member.name ?: member.email,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = "${member.email} · " +
                                            if (member.userId != null) "Active" else "Invite pending",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { removing = member },
                                    enabled = !state.saving,
                                ) {
                                    Text("Remove", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Invoicing") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = form.invoicePrefix,
                        onValueChange = { form = form.copy(invoicePrefix = it) },
                        label = { Text("Invoice number prefix") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = form.vatRegistered,
                            onCheckedChange = { form = form.copy(vatRegistered = it) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("VAT registered", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (form.vatRegistered) {
                        OutlinedTextField(
                            value = form.vatNumber,
                            onValueChange = { form = form.copy(vatNumber = it) },
                            label = { Text("VAT number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = form.vatRate,
                            onValueChange = { form = form.copy(vatRate = it) },
                            label = { Text("VAT rate (%)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = form.paymentTerms,
                        onValueChange = { form = form.copy(paymentTerms = it) },
                        label = { Text("Payment terms") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.bankDetails,
                        onValueChange = { form = form.copy(bankDetails = it) },
                        label = { Text("Bank details") },
                        placeholder = { Text("Sort code 00-00-00\nAccount 12345678") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            SectionCard(title = "Tax") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "How much of each invoice to put aside for self-assessment tax. " +
                            "Tax is on profit, so set this after your running costs — 25-30% is typical.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    var rateText by remember(taxRate) { mutableStateOf(taxRate.toString()) }
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { text ->
                            rateText = text
                            text.toDoubleOrNull()?.let { TaxReservePrefs.set(context, it) }
                        },
                        label = { Text("Set aside %") },
                        singleLine = true,
                        supportingText = {
                            Text("Shown on every invoice and as a tax-year pot on the dashboard.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            SectionCard(title = "Security") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require app lock", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Ask for your fingerprint or phone PIN when the app reopens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = appLockOn,
                        onCheckedChange = { checked ->
                            AppLock.setEnabled(context, checked)
                            appLockOn = checked
                        },
                    )
                }
            }
        }

        item {
            SectionCard(title = "Export for your accountant") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Creates a spreadsheet (CSV) you can send by email, save to Drive or open in Excel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val names = state.clients.associate { it.id to it.name }
                    OutlinedButton(
                        onClick = {
                            shareCsv(context, "invoices", invoicesCsv(state.invoices) { names[it] ?: "Client" })
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export invoices") }
                    OutlinedButton(
                        onClick = {
                            shareCsv(context, "visits", visitsCsv(state.visits) { names[it] ?: "Client" })
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export visits") }
                    OutlinedButton(
                        onClick = {
                            shareCsv(
                                context,
                                "tax-year",
                                taxYearCsv(state.invoices, { names[it] ?: "Client" }, taxRate),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export tax year (${ukTaxYearLabel(ukTaxYearContaining(LocalDate.now()))})")
                    }
                    Text(
                        text = "The tax year CSV adds what to set aside for tax at your rate in Settings → Tax, alongside what remains after it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Invoice email") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = form.emailSubjectTemplate,
                        onValueChange = { form = form.copy(emailSubjectTemplate = it) },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.emailBodyTemplate,
                        onValueChange = { form = form.copy(emailBodyTemplate = it) },
                        label = { Text("Body") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Placeholders: {{client_name}}, {{business_name}}, {{invoice_number}}, {{total}}, {{due_clause}}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Quotes") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = form.quoteValidityDays,
                        onValueChange = { form = form.copy(quoteValidityDays = it) },
                        label = { Text("Quote validity (days)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.quoteTerms,
                        onValueChange = { form = form.copy(quoteTerms = it) },
                        label = { Text("Default quote terms") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondary,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "The Monzo bank feed is not included in this build. Invoices can still be marked paid by hand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Button(
                onClick = ::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "Saving…" else "Save changes")
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                text = "Settings changes save on this device and sync when you have signal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove access for ${member.email}?") },
            text = { Text("They will no longer be able to sign in to this workspace on this account.") },
            confirmButton = {
                TextButton(onClick = { remove(member) }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Cancel") }
            },
        )
    }

    if (knowledgeOpen) {
        BusinessKnowledgeDialog(
            initial = state.settings?.businessProfile.orEmpty(),
            onDismiss = { knowledgeOpen = false },
            onSave = { profile ->
                knowledgeOpen = false
                scope.launch {
                    knowledgeError = null
                    try {
                        viewModel.saveBusinessKnowledge(
                            profile,
                            state.settings?.businessProfileSource,
                        )
                        knowledgeNote = if (profile.isBlank()) {
                            "Reset to the built-in profile."
                        } else {
                            "Business knowledge saved."
                        }
                        Toast.makeText(context, "Business knowledge saved", Toast.LENGTH_SHORT).show()
                    } catch (failure: Exception) {
                        knowledgeError = failure.message ?: "Could not save the profile"
                    }
                }
            },
        )
    }
}

@Composable
private fun BusinessKnowledgeDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Business knowledge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This is what Secretary reads about the business. Keep it factual " +
                        "and short. Leave it empty to go back to the built-in profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Profile") },
                    minLines = 10,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
