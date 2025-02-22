package com.tari.android.wallet.ui.fragment.contact_book.contacts

import com.tari.android.wallet.ui.fragment.contact_book.contacts.adapter.contact.ContactItem

class BadgeViewModel {
    var closeLastBadge: () -> Unit = {}
    var lastItem: ContactItem? = null

    fun openNew(item: ContactItem, closeAction: () -> Unit) {
        if (item == lastItem) return
        lastItem = item
        runCatching { closeLastBadge.invoke() }
        closeLastBadge = closeAction
    }
}