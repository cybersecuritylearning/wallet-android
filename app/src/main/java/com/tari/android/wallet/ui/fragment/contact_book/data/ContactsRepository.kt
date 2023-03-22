package com.tari.android.wallet.ui.fragment.contact_book.data

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tari.android.wallet.data.sharedPrefs.delegates.SerializableTime
import com.tari.android.wallet.event.Event
import com.tari.android.wallet.event.EventBus
import com.tari.android.wallet.ffi.FFIWallet
import com.tari.android.wallet.model.TariContact
import com.tari.android.wallet.model.TariWalletAddress
import com.tari.android.wallet.model.Tx
import com.tari.android.wallet.model.WalletError
import com.tari.android.wallet.ui.common.CommonViewModel
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.ContactDto
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.FFIContactDto
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.MergedContactDto
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.PhoneContactDto
import com.tari.android.wallet.ui.fragment.contact_book.data.contacts.YatContactDto
import com.tari.android.wallet.ui.fragment.contact_book.data.localStorage.ContactSharedPrefRepository
import contacts.core.Contacts
import contacts.core.Fields
import contacts.core.entities.NewName
import contacts.core.entities.NewOptions
import contacts.core.entities.NewRawContact
import contacts.core.equalTo
import contacts.core.util.names
import contacts.core.util.setName
import contacts.core.util.setOptions
import io.reactivex.subjects.BehaviorSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.joda.time.DateTime
import timber.log.Timber
import yat.android.sdk.models.PaymentAddressResponseResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.system.measureNanoTime

@Singleton
class ContactsRepository @Inject constructor(
    val context: Context,
    private val contactSharedPrefRepository: ContactSharedPrefRepository
) : CommonViewModel() {
    var publishSubject = BehaviorSubject.create<MutableList<ContactDto>>()

    val ffiBridge = FFIContactsRepositoryBridge()
    val phoneBookRepositoryBridge = PhoneBookRepositoryBridge()

    val contacts = Contacts(context)

    val loadingState = MutableLiveData<LoadingState>()

    val contactPermission = MutableLiveData(false)

    class LoadingState(val isLoading: Boolean, val name: String, val time: Double = 0.0)

    init {
        doWithLoading("Parsing from shared prefs") {
            val saved = contactSharedPrefRepository.getSavedContacts()
            publishSubject.onNext(saved.toMutableList())
        }
    }

    private fun doWithLoading(name: String, action: () -> Unit) {
        loadingState.postValue(LoadingState(true, name))
        viewModelScope.launch(Dispatchers.IO) {
            val time = measureNanoTime { runCatching { action() } } / 1_000_000_000.0
            action()
            loadingState.postValue(LoadingState(false, name, time))
        }
    }

    fun addContact(contact: ContactDto) {
        if (contactExists(contact)) {
            withListUpdate { list ->
                list.firstOrNull { it.uuid == contact.uuid }?.let { list.remove(it) }
            }
        }

        withListUpdate {
            it.add(contact)
        }
    }

    fun toggleFavorite(contactDto: ContactDto): ContactDto {
        updateContact(contactDto.uuid) {
            it.contact.isFavorite = !it.contact.isFavorite
            it.getPhoneDto()?.shouldUpdate = true
        }
        return getByUuid(contactDto.uuid)
    }

    fun updateContactInfo(contact: ContactDto, firstName: String, surname: String, yat: String): ContactDto {
        if (!contactExists(contact)) addContact(contact)
        updateContact(contact.uuid) {
            when (val user = it.contact) {
                is YatContactDto -> {
                    user.firstName = firstName
                    user.surname = surname
                    user.yat = yat
                    if (yat.isEmpty()) {
                        contact.contact = FFIContactDto(user.walletAddress, firstName, surname, user.isFavorite)
                    }
                }

                is FFIContactDto -> {
                    user.firstName = firstName
                    user.surname = surname
                    if (yat.isNotEmpty()) {
                        contact.contact = YatContactDto(user.walletAddress, yat, listOf(), user.getAlias()).apply {
                            isFavorite = contact.contact.isFavorite
                        }
                    }
                }

                is PhoneContactDto -> {
                    user.firstName = firstName
                    user.surname = surname
                    user.shouldUpdate = true
                }

                is MergedContactDto -> {
                    user.phoneContactDto.firstName = firstName
                    user.phoneContactDto.surname = surname
                    user.phoneContactDto.shouldUpdate = true
                    user.firstName = firstName
                    user.surname = surname
                    val yatContactDto = contact.getYatDto()
                    if (yatContactDto != null) {
                        yatContactDto.yat = yat
                    } else if (yat.isNotEmpty()) {
                        user.ffiContactDto = YatContactDto(user.ffiContactDto.walletAddress, yat, listOf(), user.ffiContactDto.getAlias())
                    }
                }
            }
        }
        return getByUuid(contact.uuid)
    }

    fun linkContacts(ffiContact: ContactDto, phoneContactDto: ContactDto) {
        withListUpdate {
            it.remove(getByUuid(ffiContact.uuid))
            it.remove(getByUuid(phoneContactDto.uuid))
        }

        val mergedContact = ContactDto(MergedContactDto(ffiContact.contact as FFIContactDto, phoneContactDto.contact as PhoneContactDto))
        withListUpdate { list -> list.add(mergedContact) }
    }

    fun unlinkContact(contact: ContactDto) {
        (contact.contact as? MergedContactDto)?.let { mergedContact ->
            withListUpdate { list ->
                list.remove(getByUuid(contact.uuid))
                list.add(ContactDto(mergedContact.phoneContactDto))
                list.add(ContactDto(mergedContact.ffiContactDto))
            }
        }
    }

    fun updateRecentUsedTime(contact: ContactDto) {
        val existContact = publishSubject.value.orEmpty().firstOrNull { it.uuid == contact.uuid }
        if (existContact == null) {
            withListUpdate { list ->
                list.add(contact)
            }
        }

        updateContact(contact.uuid) {
            it.lastUsedDate = SerializableTime(DateTime.now())
        }
    }

    fun deleteContact(contactDto: ContactDto) {
        withListUpdate {
            contactDto.getPhoneDto()?.let { phoneContactDto -> phoneBookRepositoryBridge.deleteFromContactBook(phoneContactDto) }
            contactDto.getFFIDto()?.let { ffiContactDto -> ffiBridge.deleteContact(ffiContactDto) }
            it.remove(getByUuid(contactDto.uuid))
        }
    }


    private fun updateContact(contactUuid: String, silently: Boolean = false, updateAction: (contact: ContactDto) -> Unit) {
        withListUpdate(silently) {
            val foundContact = it.firstOrNull { it.uuid == contactUuid }
            foundContact?.let { contact -> updateAction.invoke(contact) }
        }
    }

    fun getByUuid(uuid: String): ContactDto = publishSubject.value!!.first { it.uuid == uuid }

    private fun contactExists(contact: ContactDto): Boolean = publishSubject.value!!.any { it.uuid == contact.uuid }

    @Synchronized
    private fun withListUpdate(silently: Boolean = false, updateAction: (list: MutableList<ContactDto>) -> Unit) {
        val value = publishSubject.value!!
        updateAction.invoke(value)
        viewModelScope.launch(Dispatchers.Main) {
            publishSubject.onNext(value)
        }

        doWithLoading("Updating contact changes to phone and FFI") {
            contactSharedPrefRepository.saveContacts(value)
            if (silently.not()) {
                ffiBridge.updateToFFI(value)
                phoneBookRepositoryBridge.updateToPhoneBook()
            }
        }
    }


    inner class FFIContactsRepositoryBridge {
        init {
            doOnConnectedToWallet { doOnConnected { subscribeToActions() } }
        }

        fun updateToFFI(list: List<ContactDto>) {
            doOnConnectedToWallet {
                doOnConnected { service ->
                    viewModelScope.launch(Dispatchers.IO) {
                        for (item in list.mapNotNull { it.getFFIDto() }) {
                            service.updateContact(item.walletAddress, item.getAlias(), item.isFavorite, WalletError())
                        }
                    }
                }
            }
        }

        fun getContactForTx(tx: Tx): ContactDto = getContactByAdress(tx.tariContact.walletAddress)

        fun getContactByAdress(address: TariWalletAddress): ContactDto =
            publishSubject.value!!.firstOrNull { it.getFFIDto()?.walletAddress == address } ?: ContactDto(FFIContactDto(address))

        private fun subscribeToActions() {
            EventBus.subscribe<Event.Transaction.TxReceived>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.TxReplyReceived>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.TxFinalized>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.InboundTxBroadcast>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.OutboundTxBroadcast>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.TxMinedUnconfirmed>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.TxMined>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.TxFauxMinedUnconfirmed>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.TxFauxConfirmed>(this) { updateRecentUsedTime(it.tx.tariContact) }
            EventBus.subscribe<Event.Transaction.TxCancelled>(this) { updateRecentUsedTime(it.tx.tariContact) }
        }

        private fun updateRecentUsedTime(tariContact: TariContact) {
            doWithLoading("Updating contact changes to phone and FFI") {
                updateFFIContacts()

                val existContact = publishSubject.value.orEmpty().firstOrNull { it.contact.extractWalletAddress() == tariContact.walletAddress }
                val contact = existContact ?: ContactDto(FFIContactDto(tariContact.walletAddress, tariContact.alias, tariContact.isFavorite))
                updateRecentUsedTime(contact)
            }
        }

        private fun updateFFIContacts() {
            doWithLoading("Updating FFI contacts") {
                try {
                    val contacts = FFIWallet.instance!!.getContacts()
                    for (contactIndex in 0 until contacts.getLength()) {
                        val actualContact = contacts.getAt(contactIndex)

                        val walletAddress = actualContact.getWalletAddress()
                        val ffiWalletAddress = TariWalletAddress(walletAddress.toString(), walletAddress.getEmojiId())
                        val alias = actualContact.getAlias()
                        val isFavorite = actualContact.getIsFavorite()

                        onFFIContactAddedOrUpdated(ffiWalletAddress, alias, isFavorite)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }

                try {
                    serviceConnection.currentState.service?.let {
                        val allTxes =
                            it.getCompletedTxs(WalletError()) + it.getCancelledTxs(WalletError()) + it.getPendingInboundTxs(WalletError()) + it.getPendingOutboundTxs(
                                WalletError()
                            )
                        val allUsers = allTxes.map { it.tariContact }.distinctBy { it.walletAddress }
                        for (user in allUsers) {
                            onFFIContactAddedOrUpdated(user.walletAddress, user.alias, user.isFavorite)
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }

        fun deleteContact(contact: FFIContactDto) {
            doWithLoading("Deleting contact") {
                doOnConnectedToWallet {
                    doOnConnected { service ->
                        viewModelScope.launch(Dispatchers.IO) {
                            service.updateContact(contact.walletAddress, "", false, WalletError())
                        }
                    }
                }
            }
        }

        private fun onFFIContactAddedOrUpdated(contact: TariWalletAddress, alias: String, isFavorite: Boolean) {
            if (ffiContactExist(contact)) {
                withFFIContact(contact) {
                    it.getFFIDto()?.let { ffiContactDto ->
                        ffiContactDto.setAlias(alias)
                        ffiContactDto.isFavorite = isFavorite
                    }
                }
            } else {
                withListUpdate {
                    it.add(ContactDto(FFIContactDto(contact, alias, isFavorite)))
                }
            }
        }

        private fun ffiContactExist(walletAddress: TariWalletAddress): Boolean =
            publishSubject.value!!.any { it.contact.extractWalletAddress() == walletAddress }

        private fun withFFIContact(walletAddress: TariWalletAddress, updateAction: (contact: ContactDto) -> Unit) {
            withListUpdate {
                val foundContact = it.firstOrNull { it.contact.extractWalletAddress() == walletAddress }
                foundContact?.let { contact -> updateAction.invoke(contact) }
            }
        }

        fun updateYatInfo(contactDto: ContactDto, entries: Map<String, PaymentAddressResponseResult>) {
            withListUpdate {
                val existContact = it.firstOrNull { it.uuid == contactDto.uuid }
                existContact?.getYatDto()?.connectedWallets = entries.map { YatContactDto.ConnectedWallet(it.key, it.value) }
            }
        }
    }

    inner class PhoneBookRepositoryBridge {

        fun clean() {
            doWithLoading("cleaning") {
                try {
                    val contactsIds = contacts.query().include(Fields.DataId).find().map { it.id }

                    Timber.e(contactsIds.joinToString(", ") + " would be removed")

                    contacts.delete().contactsWithId(contactsIds).commit()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }

                withListUpdate {
                    it.removeAll { it.getPhoneDto() != null }
                }
            }
        }

        fun addTestContacts() {
            doWithLoading("Adding test contacts") {
                try {
                    val newContacts = (1..100).map {
                        PhoneContact(
                            it.toString(),
                            it.toString(),
                            (it * 1000).toString(),
                            (it.toString() + (it * 1000).toString()),
                            "",
                            Random.nextBoolean()
                        )
                    }

                    val rawContacts = newContacts.map {
                        NewRawContact().apply {
                            this.name = NewName().apply {
                                this.givenName = it.firstName
                                this.familyName = it.surname
                                this.displayName = it.displayName
                            }
                        }
                    }

                    contacts.insert()
                        .rawContacts(rawContacts)
                        .commit()

                } catch (e: Throwable) {
                    e.printStackTrace()
                }

                loadFromPhoneBook()
            }
        }

        fun loadFromPhoneBook() {
            if (contactPermission.value != true) return

            doWithLoading("Loading contacts from phone book") {
                val contacts = getPhoneContacts()

                withListUpdate {
                    contacts.forEach { phoneContact ->
                        val existContact = it.firstOrNull { it.getPhoneDto()?.id == phoneContact.id }
                        if (existContact == null) {
                            it.add(ContactDto(phoneContact.toPhoneContactDto()))
                        } else {
                            existContact.getPhoneDto()?.let {
                                it.avatar = phoneContact.avatar
                                it.firstName = phoneContact.firstName
                                it.surname = phoneContact.surname
                                it.displayName = phoneContact.displayName
                                it.isFavorite = phoneContact.isFavorite
                            }
                        }
                    }
                }
            }
        }

        private fun getPhoneContacts(): MutableList<PhoneContact> {
            val phoneContacts = contacts.query().include(Fields.all()).find()
            val contacts = phoneContacts.map {
                val name = it.names().firstOrNull()
                PhoneContact(
                    it.id.toString(),
                    name?.givenName.orEmpty(),
                    name?.familyName.orEmpty(),
                    name?.displayName.orEmpty(),
                    it.photoUri?.toString().orEmpty(),
                    it.options?.starred ?: false
                )
            }

            return contacts.toMutableList()
        }

        fun updateToPhoneBook() {
            if (contactPermission.value == true) {
                doWithLoading("Saving updates to contact book") {
                    try {
                        withListUpdate(true) { list ->
                            val contacts = list.mapNotNull { it.getPhoneDto() }.filter { it.shouldUpdate }

                            for (item in contacts) {
                                val contact = PhoneContact(item.id, item.firstName, item.surname, item.displayName, item.avatar, item.isFavorite)
                                saveNamesToPhoneBook(contact)
                                saveStarredToPhoneBook(contact)
                                item.shouldUpdate = false
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
        }

        private fun saveNamesToPhoneBook(contact: PhoneContact) {
            val phoneContact = contacts.query().include(Fields.all()).where { Contact.Id equalTo contact.id }.find().firstOrNull()

            val updatedContact = phoneContact?.mutableCopy {
                val name = this.names().firstOrNull()?.redactedCopy()?.apply {
                    this.givenName = contact.firstName
                    this.familyName = contact.surname
                    this.displayName = contact.displayName
                }

                setName(name)
            }

            updatedContact?.let {
                contacts.update()
                    .contacts(it)
                    .commit()
            }
        }

        private fun saveStarredToPhoneBook(contact: PhoneContact) {
            val phoneContact = contacts.query().include(Fields.all()).where { Contact.Id equalTo contact.id }.find().firstOrNull()

            val updatedContact = phoneContact?.mutableCopy {
                if (options == null) {
                    setOptions(contacts, NewOptions(starred = contact.isFavorite))
                } else {
                    setOptions(contacts, options!!.mutableCopy {
                        starred = contact.isFavorite
                    })
                }
            }

            updatedContact?.let {
                contacts.update()
                    .contacts(it)
                    .commit()
            }
        }

        fun deleteFromContactBook(contact: PhoneContactDto) {
            doWithLoading("Deleting contact from contact book") {
                contacts.delete().contactsWithId(contact.id.toLong()).commit()
            }
        }
    }

    data class PhoneContact(
        val id: String,
        val firstName: String,
        val surname: String,
        val displayName: String,
        val avatar: String,
        val isFavorite: Boolean,
    ) {
        fun toPhoneContactDto(): PhoneContactDto = PhoneContactDto(id, avatar, firstName, surname, isFavorite).apply {
            this.displayName = displayName
        }
    }
}