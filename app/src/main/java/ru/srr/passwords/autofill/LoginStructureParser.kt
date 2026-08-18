package ru.srr.passwords.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import java.util.Locale

/** Parses login fields from AssistStructure (Google AutofillFramework style). */
class LoginStructureParser(
    structure: AssistStructure,
    private val readValues: Boolean = false
) {
    var usernameId: AutofillId? = null
    var passwordId: AutofillId? = null
    /** All plausible username/email/phone fields (fill all of them). */
    val usernameIds: MutableList<AutofillId> = mutableListOf()
    var focusedId: AutofillId? = null
    var focusedIsSearch: Boolean = false
    var usernameValue: String? = null
    var passwordValue: String? = null
    var submitId: AutofillId? = null
    var webDomain: String? = null
    var appPackage: String? = null
    var activityClass: String? = null

    private data class Cand(
        val id: AutofillId,
        val isPassword: Boolean,
        val isUsername: Boolean,
        val isSearch: Boolean,
        val isEditableText: Boolean,
        val value: String?,
        val order: Int
    )

    private val cands = mutableListOf<Cand>()
    private var orderSeq = 0

    init {
        appPackage = structure.activityComponent?.packageName
        activityClass = structure.activityComponent?.className
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode)
        }
        resolveIds()
    }

    fun isLoginForm(): Boolean {
        if (focusedIsSearch && passwordId == null) return false
        if (passwordId != null) return true
        if (usernameId == null) return false
        if (focusedIsSearch) return false
        val act = activityClass.orEmpty().lowercase(Locale.ROOT)
        if (act.contains("auth") || act.contains("login") || act.contains("sign") ||
            act.contains("reception") || act.contains("account") || act.contains("signin")
        ) return true
        if (!webDomain.isNullOrBlank()) return true
        return false
    }

    private fun resolveIds() {
        passwordId = cands.firstOrNull { it.isPassword }?.id
        usernameId = cands.firstOrNull { it.isUsername }?.id

        // Heuristic: editable text/phone before password
        if (usernameId == null && passwordId != null) {
            val passIdx = cands.indexOfFirst { it.id == passwordId }
            usernameId = cands.take(passIdx.coerceAtLeast(0))
                .asReversed()
                .firstOrNull { it.isEditableText && !it.isPassword && !it.isSearch }
                ?.id
                ?: cands.firstOrNull { it.isEditableText && !it.isPassword && !it.isSearch }?.id
        }

        // Focused non-password field on login form counts as username
        if (usernameId == null && passwordId != null && focusedId != null && !focusedIsSearch) {
            val focused = cands.firstOrNull { it.id == focusedId }
            if (focused != null && focused.isEditableText && !focused.isPassword) {
                usernameId = focusedId
            }
        }

        usernameIds.clear()
        val seen = LinkedHashSet<AutofillId>()
        fun add(id: AutofillId?) {
            if (id != null && seen.add(id)) usernameIds += id
        }
        add(usernameId)
        cands.filter { it.isUsername || (it.isEditableText && !it.isPassword && !it.isSearch) }
            .sortedBy { it.order }
            .forEach { add(it.id) }
        // Prefer fields before password
        if (passwordId != null) {
            val passOrder = cands.firstOrNull { it.id == passwordId }?.order ?: Int.MAX_VALUE
            val before = usernameIds.filter { id ->
                (cands.firstOrNull { it.id == id }?.order ?: 0) < passOrder
            }
            if (before.isNotEmpty()) {
                usernameIds.clear()
                usernameIds.addAll(before)
                usernameId = before.first()
            }
        }

        cands.firstOrNull { it.id == usernameId }?.value?.let { usernameValue = it }
        cands.firstOrNull { it.id == passwordId }?.value?.let { passwordValue = it }
    }

    private fun walk(node: AssistStructure.ViewNode) {
        node.webDomain?.let { if (webDomain.isNullOrBlank()) webDomain = it }
        val id = node.autofillId
        val attrs = htmlAttrs(node)
        val hint = (node.autofillHints?.joinToString(" ") ?: "").lowercase(Locale.ROOT)
        val html = (node.htmlInfo?.tag ?: "").lowercase(Locale.ROOT)
        val cls = (node.className ?: "").lowercase(Locale.ROOT)
        val rawId = (node.idEntry ?: "").lowercase(Locale.ROOT)
        val hintText = (node.hint ?: "").lowercase(Locale.ROOT)
        val desc = (node.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
        val textContent = (node.text?.toString() ?: "").lowercase(Locale.ROOT)
        val combo = "$hint $html $cls $rawId $hintText $attrs $textContent $desc"
        val input = node.inputType
        val classMask = input and InputType.TYPE_MASK_CLASS
        val variation = input and InputType.TYPE_MASK_VARIATION

        val isSearch = comboContainsSearch(combo) ||
            hint.contains("search") ||
            attrs.contains("search")

        val isPassword = hint.contains("password") ||
            hint.contains(View.AUTOFILL_HINT_PASSWORD) ||
            combo.contains("password") ||
            combo.contains("passwd") ||
            combo.contains("парол") ||
            attrs.contains("password") ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        val isUsernameHint = !isPassword && !isSearch && (
            hint.contains("username") || hint.contains("email") || hint.contains("phone") ||
                hint.contains(View.AUTOFILL_HINT_USERNAME) ||
                hint.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS) ||
                hint.contains(View.AUTOFILL_HINT_PHONE) ||
                combo.contains("username") || combo.contains("email") ||
                combo.contains("login") || combo.contains("логин") ||
                combo.contains("почт") || combo.contains("телефон") ||
                combo.contains("phone") || combo.contains("электрон") ||
                combo.contains("e-mail") || combo.contains("userid") ||
                combo.contains("user_id") || combo.contains("mobile") ||
                combo.contains("account") ||
                attrs.contains("email") || attrs.contains("tel") || attrs.contains("username") ||
                attrs.contains("login") || attrs.contains("type=email") || attrs.contains("type=tel") ||
                (attrs.contains("type=text") && (combo.contains("user") || combo.contains("login"))) ||
                classMask == InputType.TYPE_CLASS_PHONE ||
                (classMask == InputType.TYPE_CLASS_NUMBER && !isPassword) ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            )

        val editable = node.isEnabled && (
            cls.contains("edittext") ||
                cls.contains("textinputedittext") ||
                cls.contains("autocompletetextview") ||
                cls.contains("textview") && node.isFocusable && classMask != 0 ||
                html == "input" ||
                node.autofillType == View.AUTOFILL_TYPE_TEXT ||
                classMask == InputType.TYPE_CLASS_TEXT ||
                classMask == InputType.TYPE_CLASS_PHONE ||
                classMask == InputType.TYPE_CLASS_NUMBER
            )
        val isSubmit = node.isEnabled && node.isClickable && !editable && (
            combo.contains("войти") ||
                combo.contains("вход") ||
                combo.contains("login") ||
                combo.contains("log in") ||
                combo.contains("sign in") ||
                combo.contains("signin") ||
                combo.contains("submit") ||
                combo.contains("продолжить")
            )

        val textVal = when {
            !readValues -> null
            node.autofillValue?.isText == true -> node.autofillValue?.textValue?.toString()
            !node.text.isNullOrBlank() -> node.text.toString()
            else -> null
        }

        if (id != null) {
            if (submitId == null && isSubmit) {
                submitId = id
            }
            if (node.isFocused) {
                focusedId = id
                focusedIsSearch = isSearch
            }
            if (editable || isPassword || isUsernameHint) {
                cands += Cand(
                    id = id,
                    isPassword = isPassword,
                    isUsername = isUsernameHint,
                    isSearch = isSearch,
                    isEditableText = editable && !isPassword,
                    value = textVal,
                    order = orderSeq++
                )
            }
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i))
    }

    private fun comboContainsSearch(combo: String): Boolean {
        val keys = listOf(
            "search", "query", "ищем", "поиск", "найти", "искать",
            "filter", "что ищем", "find", "lookup"
        )
        return keys.any { combo.contains(it) }
    }

    private fun htmlAttrs(node: AssistStructure.ViewNode): String {
        val info = node.htmlInfo ?: return ""
        val pairs = info.attributes ?: return ""
        return buildString {
            for (p in pairs) {
                append(p.first).append('=').append(p.second).append(' ')
            }
        }.lowercase(Locale.ROOT)
    }
}
