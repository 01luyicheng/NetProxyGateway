package com.netproxy.gateway.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.ui.theme.NetProxyGatewayTheme
import com.netproxy.gateway.ui.screens.MainScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    /**
     * 为 Activity 设置经 `AppLocale.wrap` 包装的基础 Context，以应用自定义区域设置。
     *
     * @param newBase 原始的基础 `Context`，在传入 `super.attachBaseContext` 前会被包装以注入/切换应用的区域设置。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /**
     * 设置活动的 Compose UI 内容并显示主界面。
     *
     * 在活动创建时应用 NetProxyGatewayTheme，使用填充全屏且采用主题背景色的 Surface 容器渲染 MainScreen。
     *
     * @param savedInstanceState 包含由系统或先前状态保存的活动状态；如果没有则为 `null`。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NetProxyGatewayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
