package com.ediposouza.teslesgendstracker.ui.news

import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.customtabs.CustomTabsIntent
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.ediposouza.teslesgendstracker.NEWS_DATE_PATTERN
import com.ediposouza.teslesgendstracker.PREF_NEWS_CHECK_LAST_TIME
import com.ediposouza.teslesgendstracker.R
import com.ediposouza.teslesgendstracker.data.News
import com.ediposouza.teslesgendstracker.interactor.FirebaseParsers
import com.ediposouza.teslesgendstracker.interactor.PublicInteractor
import com.ediposouza.teslesgendstracker.ui.base.BaseAdsFirebaseAdapter
import com.ediposouza.teslesgendstracker.ui.base.BaseFragment
import com.ediposouza.teslesgendstracker.ui.base.CmdUpdateTitle
import com.ediposouza.teslesgendstracker.ui.util.firebase.OnLinearLayoutItemScrolled
import com.ediposouza.teslesgendstracker.util.inflate
import com.ediposouza.teslesgendstracker.util.loadFromUrl
import jp.wasabeef.recyclerview.animators.SlideInRightAnimator
import kotlinx.android.synthetic.main.fragment_news.*
import kotlinx.android.synthetic.main.itemlist_news.view.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.runOnUiThread
import org.jsoup.Jsoup
import org.threeten.bp.Duration
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.format.FormatStyle
import timber.log.Timber
import java.util.*

/**
 * Created by EdipoSouza on 1/21/17.
 */
class NewsFragment : BaseFragment() {

    private val ADS_EACH_ITEMS = 8
    private val NEWS_PAGE_SIZE = 15

    private val newsRef = { publicInteractor.getNewsRef() }
    private val publicInteractor by lazy { PublicInteractor() }

    private val newsAdapter by lazy {
        object : BaseAdsFirebaseAdapter<FirebaseParsers.NewsParser, NewsViewHolder>(
                FirebaseParsers.NewsParser::class.java, newsRef, NEWS_PAGE_SIZE,
                ADS_EACH_ITEMS, R.layout.itemlist_news_ads) {

            override fun onCreateDefaultViewHolder(parent: ViewGroup): NewsViewHolder {
                return NewsViewHolder(parent.inflate(R.layout.itemlist_news))
            }

            override fun onBindContentHolder(itemKey: String, model: FirebaseParsers.NewsParser, viewHolder: NewsViewHolder) {
                viewHolder.bind(model.toNews(itemKey))
            }

            override fun onSyncEnd() {
                news_refresh_layout.isRefreshing = false
            }

        }
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return container?.inflate(R.layout.fragment_news)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        eventBus.post(CmdUpdateTitle(R.string.news_title))
        with(news_recycler_view) {
            layoutManager = object : LinearLayoutManager(context) {
                override fun supportsPredictiveItemAnimations(): Boolean = false
            }
            adapter = newsAdapter
            itemAnimator = SlideInRightAnimator()
            setHasFixedSize(true)
            addOnScrollListener(OnLinearLayoutItemScrolled(newsAdapter.getContentCount() - 3) {
                view?.post { newsAdapter.more() }
            })
        }
        news_refresh_layout.setOnRefreshListener {
            news_refresh_layout.isRefreshing = false
            newsAdapter.reset()
        }
        with(PreferenceManager.getDefaultSharedPreferences(context)) {
            val twoHoursBefore = LocalDateTime.now().minusHours(2)
            val lastNewsCheckDateText = getString(PREF_NEWS_CHECK_LAST_TIME, twoHoursBefore.toString())
            val lastNewsCheckDate = LocalDateTime.parse(lastNewsCheckDateText)
            if (Duration.between(lastNewsCheckDate, LocalDateTime.now()).toHours() > 2) {
                edit().putString(PREF_NEWS_CHECK_LAST_TIME, LocalDateTime.now().toString()).apply()
                Timber.d("checkLatestNews")
                checkLatestNews()
            } else {
                Timber.d("not in time")
                news_loading.visibility = View.GONE
            }
        }
    }

    private fun checkLatestNews() {
        doAsync {
            val latestNews = Jsoup.connect(getString(R.string.news_link))
                    .timeout(resources.getInteger(R.integer.jsoup_timeout))
                    .userAgent(getString(R.string.jsoup_user_agent))
                    .referrer(getString(R.string.jsoup_referrer))
                    .get()
                    .select("ul li.zz-abstract__item")
                    .map {
                        val cover = it.select(".zz-abstract__image").first().attr("src")
                        val title = it.select(".zz-abstract__title").first().ownText()
                        val type = it.select(".zz-abstract__category").first().ownText()
                        val date = it.select(".zz-abstract__date").first().ownText()
                        val link = it.select("a[href]").first().attr("href")
                        val newsDate = LocalDate.parse(date, DateTimeFormatter.ofPattern(NEWS_DATE_PATTERN, Locale.US))
                        News(title, type, cover, getString(R.string.news_base_link) + link, newsDate)
                    }
            context.runOnUiThread {
                val savedNewsUuids = newsAdapter.mSnapshots.getItems().map { it.first }
                latestNews.filter { !savedNewsUuids.contains(it.uuidDate) }.forEach {
                    publicInteractor.saveNews(it) {
                        Timber.i("Latest news ${it.uuidDate} saved")
                    }
                }
                news_loading.visibility = View.GONE
            }
        }
    }

    class NewsViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        fun bind(news: News) {
            with(itemView) {
                news_title.text = news.title
                news_type.text = news.type
                news_date.text = news.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
                news_cover_loading.visibility = View.VISIBLE
                news_cover.loadFromUrl(news.cover, ContextCompat.getDrawable(context, R.drawable.news_cover)) {
                    news_cover_loading.visibility = View.GONE
                }
                news_item.setOnClickListener {
                    CustomTabsIntent.Builder()
                            .setToolbarColor(ContextCompat.getColor(context, R.color.colorPrimary))
                            .setStartAnimations(context, R.anim.slide_in_right, R.anim.slide_out_left)
                            .setExitAnimations(context, R.anim.slide_in_left, R.anim.slide_out_right)
                            .build()
                            .launchUrl(context, Uri.parse(news.link))
                }
            }
        }

    }

}