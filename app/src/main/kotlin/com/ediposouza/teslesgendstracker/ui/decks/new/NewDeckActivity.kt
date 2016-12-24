package com.ediposouza.teslesgendstracker.ui.decks.new

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.CoordinatorLayout
import android.support.v4.app.ActivityCompat
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import com.ediposouza.teslesgendstracker.MetricScreen
import com.ediposouza.teslesgendstracker.R
import com.ediposouza.teslesgendstracker.data.Attribute
import com.ediposouza.teslesgendstracker.data.Class
import com.ediposouza.teslesgendstracker.data.DeckType
import com.ediposouza.teslesgendstracker.data.Patch
import com.ediposouza.teslesgendstracker.interactor.PrivateInteractor
import com.ediposouza.teslesgendstracker.interactor.PublicInteractor
import com.ediposouza.teslesgendstracker.manager.MetricsManager
import com.ediposouza.teslesgendstracker.ui.base.BaseActivity
import com.ediposouza.teslesgendstracker.ui.base.CmdShowCardsByAttr
import com.ediposouza.teslesgendstracker.ui.base.CmdUpdateRarityMagikaFiltersVisibility
import com.ediposouza.teslesgendstracker.ui.cards.CmdFilterClass
import com.ediposouza.teslesgendstracker.ui.cards.CmdFilterMagika
import com.ediposouza.teslesgendstracker.ui.cards.CmdFilterRarity
import com.ediposouza.teslesgendstracker.ui.decks.CmdAddCard
import com.ediposouza.teslesgendstracker.ui.decks.CmdRemAttr
import kotlinx.android.synthetic.main.activity_new_deck.*
import kotlinx.android.synthetic.main.dialog_new_deck.view.*
import org.greenrobot.eventbus.Subscribe
import org.jetbrains.anko.alert
import org.jetbrains.anko.intentFor
import org.jetbrains.anko.toast

class NewDeckActivity : BaseActivity() {

    companion object {

        val DECK_PRIVATE_EXTRA = "privateExtra"

    }

    val ANIM_DURATION = 250L

    val attrFilterClick: (Attribute) -> Unit = {
        eventBus.post(CmdShowCardsByAttr(it))
        new_deck_attr_filter.selectAttr(it, true)
        new_deck_attr_filter.lastAttrSelected = it
        updateDualFilter()
    }

    val onCardlistChange = {
        val cards = new_deck_cardlist.getCards()
        new_deck_cardlist_costs.updateCosts(cards)
        new_deck_cardlist_qtd.text = getString(R.string.new_deck_card_list_qtd, cards.sumBy { it.qtd.toInt() })
        new_deck_cardlist_soul.text = cards.map { it.card.rarity.soulCost * it.qtd }.sum().toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_deck)
        setResult(Activity.RESULT_CANCELED, Intent())
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar_title.text = getString(R.string.new_deck_title)
        with(new_deck_attr_filter) {
            filterClick = attrFilterClick
            onAttrLock = { attr1: Attribute, attr2: Attribute ->
                val deckCls = Class.getClasses(listOf(attr1, attr2)).first()
                new_deck_class_cover.setImageResource(deckCls.imageRes)
                toolbar_title.text = getString(R.string.new_deck_class_title, deckCls.name.toLowerCase().capitalize())
                val outValue = TypedValue()
                resources.getValue(R.dimen.deck_class_cover_alpha, outValue, true)
                new_deck_class_cover.animate().alpha(outValue.float).setDuration(ANIM_DURATION).start()
            }
            onAttrUnlock = {
                toolbar_title.text = getString(R.string.new_deck_title)
                new_deck_class_cover.animate().alpha(0f).setDuration(ANIM_DURATION).start()
            }
        }
        new_deck_cardlist.editMode = true
        new_deck_cardlist.onCardListChange = onCardlistChange
        new_deck_filter_rarity.filterClick = { eventBus.post(CmdFilterRarity(it)) }
        new_deck_filter_magika.filterClick = { eventBus.post(CmdFilterMagika(it)) }
        supportFragmentManager.beginTransaction()
                .replace(R.id.new_deck_fragment_cards, NewDeckCardsListFragment())
                .commit()
        Handler().postDelayed({
            eventBus.post(CmdShowCardsByAttr(Attribute.STRENGTH))
        }, DateUtils.SECOND_IN_MILLIS)
        MetricsManager.trackScreen(MetricScreen.SCREEN_NEW_DECKS())
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_sets, menu)
        menuInflater.inflate(R.menu.menu_done, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> {
                ActivityCompat.finishAfterTransition(this)
                return true
            }
            R.id.menu_done -> {
                showSaveDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showSaveDialog() {
        val view = View.inflate(this@NewDeckActivity, R.layout.dialog_new_deck, null)
        val deckTypes = DeckType.values().filter { it != DeckType.ARENA }.map { it.name.toLowerCase().capitalize() }
        view.new_deck_dialog_type_spinner.adapter = ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, deckTypes)
        var deckPatches = listOf<Patch>()
        PublicInteractor().getPatches {
            deckPatches = it
            val deckPatchesDesc = deckPatches.map { it.desc }.reversed()
            view.new_deck_dialog_patch_spinner.adapter = ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_dropdown_item, deckPatchesDesc)
        }
        alert {
            customView(view)
            positiveButton(R.string.new_deck_save_dialog_save) { saveDeck(view, deckPatches) }
            cancelButton { }
        }.show()
    }

    private fun saveDeck(view: View, deckPatches: List<Patch>) {
        val deckName = view.new_deck_dialog_name.text.toString()
        val deckCls = Class.getClasses(listOf(new_deck_attr_filter.lockAttr1 ?: Attribute.NEUTRAL,
                new_deck_attr_filter.lockAttr2 ?: Attribute.NEUTRAL)).first()
        val deckTypeText = view.new_deck_dialog_type_spinner.selectedItem as String
        val deckTypeSelected = DeckType.valueOf(deckTypeText.toUpperCase())
        val deckPatchDesc = view.new_deck_dialog_patch_spinner.selectedItem as String
        val deckPatchSelected = deckPatches.find { it.desc == deckPatchDesc } ?: deckPatches.last()
        val deckCost = new_deck_cardlist_soul.text.toString().toInt()
        val deckCards = new_deck_cardlist.getCards().map { it.card.shortName to it.qtd }.toMap()
        val deckPrivate = !view.new_deck_dialog_public.isChecked
        PrivateInteractor().saveDeck(deckName, deckCls, deckTypeSelected, deckCost,
                deckPatchSelected.uidDate, deckCards, deckPrivate) {
            toast(if (deckPrivate) R.string.new_deck_save_as_private else R.string.new_deck_save_as_public)
            val data = intentFor<NewDeckActivity>(DECK_PRIVATE_EXTRA to deckPrivate)
            setResult(Activity.RESULT_OK, data)
            ActivityCompat.finishAfterTransition(this)
        }
    }

    private fun updateDualFilter() {
        if (new_deck_attr_filter.lastAttrSelected == Attribute.DUAL) {
            val cls = Class.getClasses(listOf(new_deck_attr_filter.lockAttr1 ?: Attribute.NEUTRAL,
                    new_deck_attr_filter.lockAttr2 ?: Attribute.NEUTRAL)).first()
            eventBus.post(CmdFilterClass(cls))
        }
    }

    @Subscribe
    fun onCmdCardAdd(cmdCardAdd: CmdAddCard) {
        new_deck_cardlist.addCard(cmdCardAdd.card)
        new_deck_attr_filter.lockAttrs(cmdCardAdd.card.dualAttr1, cmdCardAdd.card.dualAttr2)
        new_deck_cardlist_costs.updateCosts(new_deck_cardlist.getCards())
        updateDualFilter()
    }

    @Subscribe
    fun onCmdRemAttr(cmdRemAttr: CmdRemAttr) {
        new_deck_attr_filter.unlockAttr(cmdRemAttr.attr)
        new_deck_cardlist_costs.updateCosts(new_deck_cardlist.getCards())
        updateDualFilter()
    }

    @Subscribe
    fun onCmdUpdateRarityMagikaFilters(update: CmdUpdateRarityMagikaFiltersVisibility) {
        val filterMagikaLP = new_deck_filter_magika.layoutParams as CoordinatorLayout.LayoutParams
        val filterRarityLP = new_deck_filter_rarity.layoutParams as CoordinatorLayout.LayoutParams
        val showBottomMargin = resources.getDimensionPixelSize(R.dimen.large_margin)
        val hideBottomMargin = -resources.getDimensionPixelSize(R.dimen.filter_hide_height)
        if (update.show && filterMagikaLP.bottomMargin == showBottomMargin ||
                !update.show && filterMagikaLP.bottomMargin == hideBottomMargin) {
            return
        }
        val animFrom = if (update.show) hideBottomMargin else showBottomMargin
        val animTo = if (update.show) showBottomMargin else hideBottomMargin
        with(ValueAnimator.ofInt(animFrom, animTo)) {
            duration = DateUtils.SECOND_IN_MILLIS
            addUpdateListener {
                filterRarityLP.bottomMargin = it.animatedValue as Int
                filterMagikaLP.bottomMargin = it.animatedValue as Int
                new_deck_filter_magika.layoutParams = filterMagikaLP
                new_deck_filter_rarity.layoutParams = filterRarityLP
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(p0: Animator?) {
                }

                override fun onAnimationEnd(p0: Animator?) {
                }

                override fun onAnimationCancel(p0: Animator?) {
                }

                override fun onAnimationStart(p0: Animator?) {
                }

            })
            start()
        }
    }

}