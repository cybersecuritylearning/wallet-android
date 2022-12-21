package com.tari.android.wallet.ui.fragment.send.amountView

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import com.tari.android.wallet.R
import com.tari.android.wallet.databinding.ViewAmountBinding
import com.tari.android.wallet.model.MicroTari
import com.tari.android.wallet.ui.common.CommonViewModel
import com.tari.android.wallet.ui.component.common.CommonView
import com.tari.android.wallet.ui.extension.colorFromAttribute

class AmountView : CommonView<CommonViewModel, ViewAmountBinding> {
    constructor(context: Context) : super(context, null)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun setup() = Unit

    override fun bindingInflate(layoutInflater: LayoutInflater, parent: ViewGroup?, attachToRoot: Boolean): ViewAmountBinding =
        ViewAmountBinding.inflate(layoutInflater, parent, attachToRoot)

    fun setupArgs(textSize: Float) {
        ui.balanceView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
    }

    fun setupArgs(args: MicroTari) {
        ui.balanceView.text = args.formattedTariValue
    }

    fun setupArgs(style: AmountStyle) {
        val color = when(style) {
            AmountStyle.Normal -> R.attr.palette_text_heading
            AmountStyle.Warning -> R.attr.palette_system_red
        }
        val intColor = context.colorFromAttribute(color)
        ui.gem.setColorFilter(intColor)
        ui.balanceView.setTextColor(intColor)
    }
}