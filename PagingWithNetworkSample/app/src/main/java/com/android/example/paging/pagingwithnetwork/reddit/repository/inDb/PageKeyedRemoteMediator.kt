package com.android.example.paging.pagingwithnetwork.reddit.repository.inDb

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.LoadType.*
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.android.example.paging.pagingwithnetwork.reddit.api.RedditApi
import com.android.example.paging.pagingwithnetwork.reddit.db.RedditDb
import com.android.example.paging.pagingwithnetwork.reddit.db.RedditPostDao
import com.android.example.paging.pagingwithnetwork.reddit.db.RedditPostRemoteKeyDao
import com.android.example.paging.pagingwithnetwork.reddit.vo.RedditPost
import com.android.example.paging.pagingwithnetwork.reddit.vo.RedditPostRemoteKey
import retrofit2.HttpException
import java.io.IOException

@OptIn(ExperimentalPagingApi::class)
class PageKeyedRemoteMediator(
    private val db: RedditDb,
    private val redditApi: RedditApi,
    private val subredditName: String
) : RemoteMediator<Int, RedditPost>() {
    private val postDao: RedditPostDao = db.posts()
    private val remoteKeyDao: RedditPostRemoteKeyDao = db.remoteKeys()

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, RedditPost>
    ): MediatorResult {
        try {
            // Get the closest item from PagingState that we want to load data around.
            val loadKey = when (loadType) {
                REFRESH -> null
                PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                APPEND -> {
                    val redditPost = state.pages
                        .lastOrNull { it.data.isNotEmpty() }
                        ?.data?.lastOrNull()

                    // Query DB for RedditPostPageKey corresponding to the item above.
                    // RedditPostPageKey is a wrapper object we use to keep track of page keys we
                    // receive from the Reddit API to fetch the next or previous page.
                    val postKey = db.withTransaction {
                        redditPost?.let { remoteKeyDao.remoteKeyByPost(it.name, it.subreddit) }
                    }

                    // We must explicitly check if the page key is null when appending, since the
                    // Reddit API informs the end of the list by returning null for page key, but
                    // passing a null key to Reddit API will fetch the initial page.
                    if (postKey == null) {
                        return MediatorResult.Success(endOfPaginationReached = true)
                    }

                    postKey.nextPageKey
                }
            }

            val data = redditApi.getTop(
                subreddit = subredditName,
                after = loadKey,
                before = null,
                limit = if (loadType == REFRESH) state.config.initialLoadSize else state.config.pageSize
            ).data

            val items = data.children.map { it.data }

            db.withTransaction {
                if (loadType == REFRESH) {
                    postDao.deleteBySubreddit(subredditName)
                    remoteKeyDao.deleteBySubreddit(subredditName)
                }

                remoteKeyDao.insertAll(
                    items.map {
                        RedditPostRemoteKey(it.name, it.subreddit, data.before, data.after)
                    }
                )

                postDao.insertAll(items)
            }

            return MediatorResult.Success(endOfPaginationReached = items.isEmpty())
        } catch (e: IOException) {
            return MediatorResult.Error(e)
        } catch (e: HttpException) {
            return MediatorResult.Error(e)
        }
    }
}
