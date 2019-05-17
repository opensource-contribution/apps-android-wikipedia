package org.wikipedia.suggestededits

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.fragment_suggested_edits_add_descriptions.*
import org.wikipedia.Constants.ACTIVITY_REQUEST_DESCRIPTION_EDIT
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.Constants.InvokeSource.EDIT_FEED_TITLE_DESC
import org.wikipedia.Constants.InvokeSource.EDIT_FEED_TRANSLATE_TITLE_DESC
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.analytics.RandomizerFunnel
import org.wikipedia.analytics.SuggestedEditsFunnel
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.mwapi.SiteMatrix
import org.wikipedia.descriptions.DescriptionEditActivity
import org.wikipedia.page.PageTitle
import org.wikipedia.suggestededits.SuggestedEditsAddDescriptionsActivity.Companion.EXTRA_SOURCE
import org.wikipedia.suggestededits.SuggestedEditsAddDescriptionsActivity.Companion.EXTRA_SOURCE_ADDED_DESCRIPTION
import org.wikipedia.util.AnimationUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.log.L

class SuggestedEditsAddDescriptionsFragment : Fragment() {
    private val viewPagerListener = ViewPagerListener()
    private var funnel: RandomizerFunnel? = null
    private val disposables = CompositeDisposable()
    private val app = WikipediaApp.getInstance()
    private var siteMatrix: SiteMatrix? = null
    private var languageList: MutableList<String> = mutableListOf()
    private var languageToList: MutableList<String> = mutableListOf()
    private var languageCodesToList: MutableList<String> = arrayListOf()
    var langFromCode: String = app.language().appLanguageCode
    var langToCode: String = if (app.language().appLanguageCodes.size == 1) "" else app.language().appLanguageCodes[1]
    var source: InvokeSource = EDIT_FEED_TITLE_DESC

    private val topTitle: PageTitle?
        get() {
            val f = topChild
            return if (source == EDIT_FEED_TITLE_DESC) {
                titleFromPageName(f?.title, f?.addedDescription)
            } else {
                f?.targetPageTitle?.description = f?.addedDescription
                f?.targetPageTitle
            }
        }

    private val topChild: SuggestedEditsAddDescriptionsItemFragment?
        get() {
            val fm = fragmentManager
            for (f in fm!!.fragments) {
                if (f is SuggestedEditsAddDescriptionsItemFragment && f.pagerPosition == addTitleDescriptionsItemPager.currentItem) {
                    return f
                }
            }
            return null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true

        // Record the first impression, since the ViewPager doesn't send an event for the first topmost item.
        SuggestedEditsFunnel.get().impression(source)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        source = arguments?.getSerializable(EXTRA_SOURCE) as InvokeSource
        return inflater.inflate(R.layout.fragment_suggested_edits_add_descriptions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setInitialUiState()
        wikiFromLanguageSpinner.onItemSelectedListener = OnFromSpinnerItemSelectedListener()
        wikiToLanguageSpinner.onItemSelectedListener = OnToSpinnerItemSelectedListener()

        addTitleDescriptionsItemPager.offscreenPageLimit = 2
        addTitleDescriptionsItemPager.setPageTransformer(true, AnimationUtil.PagerTransformerWithoutPreviews())
        addTitleDescriptionsItemPager.addOnPageChangeListener(viewPagerListener)

        resetTitleDescriptionItemAdapter()

        if (languageList.isEmpty()) {
            // Fragment is created for the first time.
            requestLanguagesAndBuildSpinner()
        } else {
            // Fragment already exists, so just update the UI.
            updateFromLanguageSpinner()
        }

        arrow.setOnClickListener {
            val pos = languageList.indexOf(languageToList[wikiToLanguageSpinner.selectedItemPosition])
            val prevFromLang = languageList[wikiFromLanguageSpinner.selectedItemPosition]
            wikiFromLanguageSpinner.setSelection(pos)
            val postDelay: Long = 100
            wikiToLanguageSpinner.postDelayed({
                if (isAdded) {
                    wikiToLanguageSpinner.setSelection(languageToList.indexOf(prevFromLang))
                }
            }, postDelay)
        }

        backButton.setOnClickListener { previousPage() }
        nextButton.setOnClickListener {
            if (nextButton.drawable is Animatable) {
                (nextButton.drawable as Animatable).start()
            }
            nextPage()
        }
        updateBackButton(0)

        addDescriptionButton.setOnClickListener { onSelectPage() }

        updateActionButton()
    }

    private fun updateBackButton(pagerPosition: Int) {
        backButton.isClickable = pagerPosition != 0
        backButton.alpha = if (pagerPosition == 0) 0.31f else 1f
    }

    private fun updateActionButton() {
        val isAddedDescriptionEmpty = topChild?.addedDescription.isNullOrEmpty()
        if (!isAddedDescriptionEmpty) topChild?.showAddedDescriptionView(topChild?.addedDescription)
        addDescriptionImage!!.setImageDrawable(requireContext().getDrawable(if (isAddedDescriptionEmpty) R.drawable.ic_add_gray_white_24dp else R.drawable.ic_mode_edit_white_24dp))
        if (source == EDIT_FEED_TRANSLATE_TITLE_DESC) {
            addDescriptionText?.text = getString(if (isAddedDescriptionEmpty) R.string.suggested_edits_add_translation_button_label else R.string.suggested_edits_edit_translation_button_label)
        } else if (addDescriptionText != null) {
            addDescriptionText?.text = getString(if (isAddedDescriptionEmpty) R.string.suggested_edits_add_description_button else R.string.description_edit_edit_description)
        }
    }

    override fun onDestroyView() {
        disposables.clear()
        addTitleDescriptionsItemPager.removeOnPageChangeListener(viewPagerListener)
        if (funnel != null) {
            funnel!!.done()
            funnel = null
        }
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        SuggestedEditsFunnel.get().pause()
    }

    override fun onResume() {
        super.onResume()
        SuggestedEditsFunnel.get().resume()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ACTIVITY_REQUEST_DESCRIPTION_EDIT && resultCode == RESULT_OK) {
            topChild?.showAddedDescriptionView(data?.getStringExtra(EXTRA_SOURCE_ADDED_DESCRIPTION))
            FeedbackUtil.showMessage(this, R.string.description_edit_success_saved_snackbar)
            nextPage()
        }
    }

    private fun previousPage() {
        viewPagerListener.setNextPageSelectedAutomatic()
        if (addTitleDescriptionsItemPager.currentItem > 0) {
            addTitleDescriptionsItemPager.setCurrentItem(addTitleDescriptionsItemPager.currentItem - 1, true)
        }
        updateActionButton()
    }

    private fun nextPage() {
        viewPagerListener.setNextPageSelectedAutomatic()
        addTitleDescriptionsItemPager.setCurrentItem(addTitleDescriptionsItemPager.currentItem + 1, true)
        updateActionButton()
    }

    private fun titleFromPageName(pageName: String?, description: String?): PageTitle {
        return PageTitle(pageName, WikiSite.forLanguageCode(if (source == EDIT_FEED_TITLE_DESC) langFromCode else langToCode), null, description)
    }

    fun onSelectPage() {
        if (topTitle != null) {
            startActivityForResult(DescriptionEditActivity.newIntent(requireContext(), topTitle!!, topChild!!.sourceSummary, topChild!!.targetSummary, source),
                    ACTIVITY_REQUEST_DESCRIPTION_EDIT)
        }
    }
  
    private fun requestLanguagesAndBuildSpinner() {
        disposables.add(ServiceFactory.get(app.wikiSite).siteMatrix
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map { siteMatrix = it; }
                .doFinally { updateFromLanguageSpinner() }
                .subscribe({
                    for (code in app.language().appLanguageCodes) {
                        languageList.add(getLanguageLocalName(code))
                    }
                }, { L.e(it) }))
    }

    private fun getLanguageLocalName(code: String): String {
        if (siteMatrix == null) {
            return app.language().getAppLanguageLocalizedName(code)!!
        }
        var name: String? = null
        for (info in SiteMatrix.getSites(siteMatrix!!)) {
            if (code == info.code()) {
                name = info.name()
                break
            }
        }
        if (name.isNullOrEmpty()) {
            name = app.language().getAppLanguageLocalizedName(code)
        }
        return name ?: code
    }

    private fun resetTitleDescriptionItemAdapter() {
        val postDelay: Long = 250
        wikiToLanguageSpinner.postDelayed({
            if (isAdded) {
                addTitleDescriptionsItemPager.adapter = ViewPagerAdapter(requireActivity() as AppCompatActivity)
            }
        }, postDelay)
    }

    private fun setInitialUiState() {
        wikiLanguageDropdownContainer.visibility = if (app.language().appLanguageCodes.size > 1
                && source == EDIT_FEED_TRANSLATE_TITLE_DESC) VISIBLE else GONE
    }

    private fun updateFromLanguageSpinner() {
        wikiFromLanguageSpinner.adapter = ArrayAdapter<String>(requireContext(), R.layout.item_language_spinner, languageList)
    }

    private fun updateToLanguageSpinner(fromLanguageSpinnerPosition: Int) {
        languageCodesToList.clear()
        languageCodesToList.addAll(app.language().appLanguageCodes)
        languageCodesToList.removeAt(fromLanguageSpinnerPosition)
        languageToList.clear()
        for (language in languageCodesToList) {
            languageToList.add(getLanguageLocalName(language))
        }

        val toAdapter = ArrayAdapter<String>(requireContext(), R.layout.item_language_spinner, languageToList)
        wikiToLanguageSpinner.adapter = toAdapter

        val pos = languageCodesToList.indexOf(langToCode)
        if (pos < 0) {
            langToCode = languageCodesToList[0]
        } else {
            wikiToLanguageSpinner.setSelection(pos)
        }
    }

    private inner class OnFromSpinnerItemSelectedListener : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (langFromCode != app.language().appLanguageCodes[position]) {
                langFromCode = app.language().appLanguageCodes[position]
                resetTitleDescriptionItemAdapter()
            }
            updateToLanguageSpinner(position)
            updateBackButton(0)
        }

        override fun onNothingSelected(parent: AdapterView<*>) {
        }
    }

    private inner class OnToSpinnerItemSelectedListener : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (langToCode != languageCodesToList[position]) {
                langToCode = languageCodesToList[position]
                resetTitleDescriptionItemAdapter()
            }
        }
        override fun onNothingSelected(parent: AdapterView<*>) {
        }
    }

    private class ViewPagerAdapter internal constructor(activity: AppCompatActivity): FragmentStatePagerAdapter(activity.supportFragmentManager) {

        override fun getCount(): Int {
            return Integer.MAX_VALUE
        }

        override fun getItem(position: Int): Fragment {
            val f = SuggestedEditsAddDescriptionsItemFragment.newInstance()
            f.pagerPosition = position
            return f
        }
    }

    private inner class ViewPagerListener : ViewPager.OnPageChangeListener {
        private var prevPosition: Int = 0
        private var nextPageSelectedAutomatic: Boolean = false

        internal fun setNextPageSelectedAutomatic() {
            nextPageSelectedAutomatic = true
        }

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

        override fun onPageSelected(position: Int) {
            updateBackButton(position)
            updateActionButton()
            if (!nextPageSelectedAutomatic && funnel != null) {
                if (position > prevPosition) {
                    funnel!!.swipedForward()
                } else if (position < prevPosition) {
                    funnel!!.swipedBack()
                }
            }

            SuggestedEditsFunnel.get().impression(source)

            nextPageSelectedAutomatic = false
            prevPosition = position
        }

        override fun onPageScrollStateChanged(state: Int) {}
    }

    companion object {
        fun newInstance(source: InvokeSource): SuggestedEditsAddDescriptionsFragment {
            val addTitleDescriptionsFragment = SuggestedEditsAddDescriptionsFragment()
            val args = Bundle()
            args.putSerializable(EXTRA_SOURCE, source)
            addTitleDescriptionsFragment.arguments = args
            return addTitleDescriptionsFragment
        }
    }
}
