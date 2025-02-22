package com.tari.android.wallet.ui.fragment.send.common

import android.os.Parcel
import android.os.Parcelable
import com.tari.android.wallet.model.MicroTari
import com.tari.android.wallet.model.TariContact
import com.tari.android.wallet.ui.extension.readP
import com.tari.android.wallet.ui.extension.readS
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.ContactDto
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.FFIContactDto
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.IContact

data class TransactionData(val recipientContact: ContactDto?, val amount: MicroTari?, val note: String?, val feePerGram: MicroTari?, val isOneSidePayment: Boolean) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readS(ContactDto::class.java),
        parcel.readP(MicroTari::class.java),
        parcel.readString(),
        parcel.readP(MicroTari::class.java),
        parcel.readInt() == 1
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeSerializable(recipientContact)
        parcel.writeParcelable(amount, flags)
        parcel.writeString(note)
        parcel.writeParcelable(feePerGram, flags)
        parcel.writeInt(if(isOneSidePayment) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<TransactionData> {
        override fun createFromParcel(parcel: Parcel): TransactionData = TransactionData(parcel)

        override fun newArray(size: Int): Array<TransactionData?> = arrayOfNulls(size)
    }
}