package com.onekey.feature.twofa.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.OtpAlgorithm
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareExposedDropdownMenu
import com.onekey.core.presentation.lockaware.LockAwareModalBottomSheet
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import com.onekey.feature.twofa.domain.OtpSecretValidator
import com.onekey.feature.twofa.presentation.viewmodel.ManualOtpEntryViewModel
import kotlinx.coroutines.delay

/**
 * Bottom-sheet form for adding a 2FA entry manually (no QR scan).
 *
 * Field set chosen to match Google Authenticator / 1Password / Bitwarden's
 * minimal default: Service / Account / Secret are always visible; everything
 * non-default (algorithm, digits, period, type, counter) lives behind an
 * "Advanced" toggle so the 99% case stays uncluttered.
 *
 * Pasted-URI handling: when the user pastes an `otpauth://...` URI into the
 * Secret field, [ManualOtpEntryViewModel.parseAsUri] populates Service /
 * Account / Advanced from the URI's parameters and replaces the field's
 * contents with the bare base32 secret. Catches the gotcha where users copy
 * "Can't scan? Use this code" links from setup pages and paste the whole URI.
 *
 * Save is gated on three things: a non-blank Service, a `Valid` validation
 * result on the Secret, and the VM not currently mid-save. Validation runs
 * after a 250ms quiet period on the secret field so the user isn't fighting
 * red text while still typing the first few characters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualOtpEntrySheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ManualOtpEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is ManualOtpEntryViewModel.State.Saved) onSaved()
    }

    var issuer by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(OtpType.TOTP.name) }
    var algorithm by rememberSaveable { mutableStateOf(OtpAlgorithm.SHA1.name) }
    var digits by rememberSaveable { mutableIntStateOf(OtpParams.DEFAULT_DIGITS) }
    var period by rememberSaveable { mutableStateOf(OtpParams.DEFAULT_PERIOD_SECONDS.toString()) }
    var counter by rememberSaveable { mutableStateOf(DEFAULT_COUNTER_TEXT) }

    var secretValidation by remember { mutableStateOf<OtpSecretValidator.Result?>(null) }

    /**
     * True when the form holds anything the user typed or any non-default
     * advanced setting. Drives the "Discard 2FA entry?" guard on every dismiss
     * path - Cancel button, swipe-to-hide, scrim tap, system back. We compare
     * advanced fields against the literal default values rather than tracking
     * a "has touched" flag because rememberSaveable only persists values, not
     * touch history; equality with defaults is the source of truth that
     * survives rotation cleanly.
     */
    val hasChanges by remember {
        derivedStateOf {
            issuer.isNotBlank() ||
                account.isNotBlank() ||
                secret.isNotBlank() ||
                type != OtpType.TOTP.name ||
                algorithm != OtpAlgorithm.SHA1.name ||
                digits != OtpParams.DEFAULT_DIGITS ||
                period != OtpParams.DEFAULT_PERIOD_SECONDS.toString() ||
                counter != DEFAULT_COUNTER_TEXT
        }
    }

    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    // Debounced validate-on-change with pasted-URI hijack. The order of operations
    // here matters: URI paste runs first and exits early, replacing every form
    // field including `secret`; the recomposition that follows re-enters this
    // effect with the cleaned secret and runs the normal validate path.
    LaunchedEffect(secret) {
        if (secret.isBlank()) {
            secretValidation = null
            return@LaunchedEffect
        }
        viewModel.parseAsUri(secret)?.let { parsed ->
            issuer = parsed.issuer
            account = parsed.account
            // STEAM is auto-detected by issuer in both the URI parser and the VM's
            // save path; the radio only exposes TOTP vs HOTP, so STEAM collapses
            // to TOTP for the radio's sake. The VM re-resolves it on save.
            type = if (parsed.params.type == OtpType.STEAM) OtpType.TOTP.name
                   else parsed.params.type.name
            algorithm = parsed.params.algorithm.name
            digits = parsed.params.digits
            period = parsed.params.period.toString()
            counter = parsed.params.counter.toString()
            advancedExpanded = true
            secret = parsed.params.secret
            return@LaunchedEffect
        }
        delay(SECRET_VALIDATION_DEBOUNCE_MILLIS)
        secretValidation = viewModel.validateSecret(secret)
    }

    val parsedType = OtpType.fromNameOrDefault(type)
    val parsedAlgorithm = OtpAlgorithm.fromNameOrDefault(algorithm)
    val periodLong = period.toLongOrNull()
    val counterLong = counter.toLongOrNull()

    val cleanedSecret = (secretValidation as? OtpSecretValidator.Result.Valid)?.cleaned
    val advancedFieldsValid = digits in OtpParams.MIN_DIGITS..OtpParams.MAX_DIGITS &&
        (periodLong != null && periodLong > 0L) &&
        (counterLong != null && counterLong >= 0L)
    val canSave = issuer.isNotBlank() &&
        cleanedSecret != null &&
        advancedFieldsValid &&
        state !is ManualOtpEntryViewModel.State.Saving

    /**
     * Block hide-attempts (swipe-down, scrim tap, system back) when the form
     * is dirty by returning `false` and surfacing the discard dialog instead.
     * Programmatic dismissal - when [onDismiss] / [onSaved] flips the parent's
     * `showSheet` flag - bypasses this guard entirely because the sheet is
     * removed from composition without going through `animateToDismiss`, which
     * is the only call site Material3 consults `confirmValueChange` from.
     *
     * The lambda captures the hasChanges State delegate (always-fresh) and the
     * showDiscardDialog setter, both Compose-snapshot-safe.
     */
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && hasChanges) {
                showDiscardDialog = true
                false
            } else {
                true
            }
        },
    )

    /**
     * System-back inside an open `ModalBottomSheet` runs through the sheet's
     * own predictive-back handling, which calls `confirmValueChange` - so the
     * guard above is sufficient. We still install a [BackHandler] gated on the
     * discard dialog being open: with the dialog up, system-back should close
     * the dialog ("Keep editing"), not unwind the discard flow.
     */
    BackHandler(enabled = showDiscardDialog) {
        showDiscardDialog = false
    }

    LockAwareModalBottomSheet(
        onDismissRequest = {
            // Reached only when `confirmValueChange` allowed the hide, which
            // means !hasChanges. Forwarding directly is safe.
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        // Hero/form split inside the sheet so the Save row stays anchored above
        // the keyboard. The sheet's contentWindowInsets defaults to systemBars
        // only (no IME), so without `.imePadding()` here the focused field's
        // bringIntoView would scroll the Save button below the visible viewport.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Add 2FA Manually",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Enter the setup key from your account's 2FA setup page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LockAwareOutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text("Service") },
                    placeholder = { Text("e.g. GitHub") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                LockAwareOutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Account") },
                    placeholder = { Text("e.g. user@example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                SecretField(
                    value = secret,
                    onValueChange = { secret = it },
                    validation = secretValidation,
                )

                AdvancedSection(
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                    type = parsedType,
                    onTypeChange = { type = it.name },
                    algorithm = parsedAlgorithm,
                    onAlgorithmChange = { algorithm = it.name },
                    digits = digits,
                    onDigitsChange = { digits = it },
                    period = period,
                    onPeriodChange = { period = it.filter { ch -> ch.isDigit() } },
                    counter = counter,
                    onCounterChange = { counter = it.filter { ch -> ch.isDigit() } },
                )

                (state as? ManualOtpEntryViewModel.State.Error)?.let { error ->
                    Text(
                        text = error.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    // Cancel routes through the same guard as swipe / back: if
                    // the form has unsaved input, surface the discard dialog
                    // instead of immediately discarding.
                    onClick = {
                        if (hasChanges) showDiscardDialog = true else onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        // canSave guards on every component of this construction; the
                        // !! / non-null assertions correspond to those gates and never
                        // fire in practice.
                        val params = OtpParams(
                            type = parsedType,
                            secret = cleanedSecret!!,
                            algorithm = parsedAlgorithm,
                            digits = digits,
                            period = periodLong!!,
                            counter = counterLong!!,
                        )
                        viewModel.save(issuer.trim(), account.trim(), params)
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state is ManualOtpEntryViewModel.State.Saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        LockAwareDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard 2FA entry?") },
            text = {
                Text(
                    "What you've entered will be lost. This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        // The parent will set its `showSheet` flag to false on
                        // this callback; that removes the sheet from composition
                        // without re-entering `confirmValueChange`, so no extra
                        // bypass flag is needed.
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing")
                }
            },
        )
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    validation: OtpSecretValidator.Result?,
) {
    val errorMessage = when (validation) {
        OtpSecretValidator.Result.Invalid.BadCharacters -> "Contains characters outside A-Z and 2-7."
        OtpSecretValidator.Result.Invalid.Empty -> null
        OtpSecretValidator.Result.Invalid.GeneratorFailure -> "This setup key isn't usable. Double-check the value."
        OtpSecretValidator.Result.Invalid.TooShort ->
            "Too short - needs at least ${OtpSecretValidator.MIN_BASE32_CHARS} characters."
        else -> null
    }
    Column {
        LockAwareOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Setup key") },
            placeholder = { Text("BASE32 secret or otpauth:// URI") },
            singleLine = true,
            isError = errorMessage != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            supportingText = {
                Text(
                    text = errorMessage ?: "Spaces will be removed automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    type: OtpType,
    onTypeChange: (OtpType) -> Unit,
    algorithm: OtpAlgorithm,
    onAlgorithmChange: (OtpAlgorithm) -> Unit,
    digits: Int,
    onDigitsChange: (Int) -> Unit,
    period: String,
    onPeriodChange: (String) -> Unit,
    counter: String,
    onCounterChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Advanced",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Hide advanced options" else "Show advanced options",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Type radio. STEAM is auto-detected by issuer name, so we expose only
            // the two RFC types here - keeps the UI pickup-and-go friendly.
            Column {
                Text(
                    text = "Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(OtpType.TOTP to "Time-based (TOTP)", OtpType.HOTP to "Counter-based (HOTP)")
                        .forEach { (option, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { onTypeChange(option) }
                                    .padding(end = 12.dp),
                            ) {
                                RadioButton(
                                    selected = type == option,
                                    onClick = { onTypeChange(option) },
                                )
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                }
            }

            EnumDropdown(
                label = "Algorithm",
                options = OtpAlgorithm.entries.map { it to it.name },
                selected = algorithm,
                onSelect = onAlgorithmChange,
            )

            EnumDropdown(
                label = "Digits",
                options = (OtpParams.MIN_DIGITS..OtpParams.MAX_DIGITS).map { it to it.toString() },
                selected = digits,
                onSelect = onDigitsChange,
            )

            if (type == OtpType.TOTP) {
                LockAwareOutlinedTextField(
                    value = period,
                    onValueChange = onPeriodChange,
                    label = { Text("Period (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LockAwareOutlinedTextField(
                    value = counter,
                    onValueChange = onCounterChange,
                    label = { Text("Counter") },
                    supportingText = { Text("First code uses this value; advances per tap.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Small dropdown helper used for Algorithm and Digits - both are bounded enums
 * the validator already constrains, so the UI need only present the legal set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: ""
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        LockAwareOutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        LockAwareExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val SECRET_VALIDATION_DEBOUNCE_MILLIS = 250L

/**
 * Initial text for the HOTP counter field. Compared against `counter` to
 * decide whether the user has touched it - kept as a constant so the
 * `hasChanges` derivation and the field's initialiser can't drift.
 */
private const val DEFAULT_COUNTER_TEXT = "0"
