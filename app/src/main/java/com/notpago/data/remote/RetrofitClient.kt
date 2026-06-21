package com.notpago.data.remote

import com.notpago.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var cachedHost: String? = null
    private var cachedApi: YapeApi? = null

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getApi(baseUrl: String): YapeApi {
        val normalizedUrl = if (baseUrl.startsWith("http")) baseUrl else "https://$baseUrl"
        if (normalizedUrl == cachedHost && cachedApi != null) return cachedApi!!
        cachedApi = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
            .create(YapeApi::class.java)
        cachedHost = normalizedUrl
        return cachedApi!!
    }
}
