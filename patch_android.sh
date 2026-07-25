#!/bin/bash
patch -p1 << 'PATCH_EOF'
--- a/android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt
+++ b/android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt
@@ -10,7 +10,9 @@ import android.content.Context
 import android.content.ContextWrapper

 import androidx.activity.compose.BackHandler
+import androidx.compose.animation.AnimatedContent
 import androidx.compose.animation.AnimatedVisibility
+import androidx.compose.animation.animateColorAsState
 import androidx.compose.animation.core.animateFloatAsState
 import androidx.compose.ui.draw.rotate
 import androidx.compose.foundation.layout.*
@@ -467,10 +469,14 @@ private fun ConnectionStatusCard(uiState: UiState, onRetry: () -> Unit) {
                 icon = Icons.Default.CloudOff
             )
     }
-    val containerColor = statusData.containerColor
-    val contentColor = statusData.contentColor
-    val statusTextRes = statusData.textRes
-    val statusIcon = statusData.icon
+    val containerColor by animateColorAsState(
+        targetValue = statusData.containerColor,
+        label = "connectionContainerColor"
+    )
+    val contentColor by animateColorAsState(
+        targetValue = statusData.contentColor,
+        label = "connectionContentColor"
+    )

     Card(
         modifier = Modifier.fillMaxWidth(),
@@ -483,29 +489,34 @@ private fun ConnectionStatusCard(uiState: UiState, onRetry: () -> Unit) {
             modifier = Modifier.padding(16.dp),
             horizontalAlignment = Alignment.CenterHorizontally
         ) {
-            Row(
-                verticalAlignment = Alignment.CenterVertically,
-                horizontalArrangement = Arrangement.spacedBy(8.dp)
-            ) {
-                if (uiState.mqttState == MqttUiState.Connecting) {
-                    CircularProgressIndicator(
-                        modifier = Modifier.size(24.dp),
-                        color = contentColor,
-                        strokeWidth = 2.dp
-                    )
-                } else if (statusIcon != null) {
-                    Icon(
-                        imageVector = statusIcon,
-                        contentDescription = null,
-                        modifier = Modifier.size(24.dp),
-                        tint = contentColor
+            AnimatedContent(
+                targetState = statusData,
+                label = "connectionStatusAnim"
+            ) { targetData ->
+                Row(
+                    verticalAlignment = Alignment.CenterVertically,
+                    horizontalArrangement = Arrangement.spacedBy(8.dp)
+                ) {
+                    if (targetData.icon == null) {
+                        CircularProgressIndicator(
+                            modifier = Modifier.size(24.dp),
+                            color = contentColor,
+                            strokeWidth = 2.dp
+                        )
+                    } else {
+                        Icon(
+                            imageVector = targetData.icon,
+                            contentDescription = null,
+                            modifier = Modifier.size(24.dp),
+                            tint = contentColor
+                        )
+                    }
+                    Text(
+                        text = stringResource(targetData.textRes),
+                        style = MaterialTheme.typography.headlineSmall,
+                        color = contentColor
                     )
                 }
-                Text(
-                    text = stringResource(statusTextRes),
-                    style = MaterialTheme.typography.headlineSmall,
-                    color = contentColor
-                )
             }

             if (uiState.peerId.isNotEmpty()) {
@@ -711,10 +722,14 @@ private fun VpnStatusCard(uiState: UiState) {
             icon = Icons.Default.VpnKey
         )
     }
-    val containerColor = vpnData.containerColor
-    val contentColor = vpnData.contentColor
-    val statusTextRes = vpnData.textRes
-    val icon = vpnData.icon
+    val containerColor by animateColorAsState(
+        targetValue = vpnData.containerColor,
+        label = "vpnContainerColor"
+    )
+    val contentColor by animateColorAsState(
+        targetValue = vpnData.contentColor,
+        label = "vpnContentColor"
+    )

     Card(
         modifier = Modifier.fillMaxWidth(),
@@ -723,40 +738,45 @@ private fun VpnStatusCard(uiState: UiState) {
             contentColor = contentColor
         )
     ) {
-        Row(
-            modifier = Modifier
-                .fillMaxWidth()
-                .padding(16.dp),
-            verticalAlignment = Alignment.CenterVertically,
-            horizontalArrangement = Arrangement.spacedBy(12.dp)
-        ) {
-            if (icon != null) {
-                Icon(
-                    imageVector = icon,
-                    contentDescription = null,
-                    modifier = Modifier.size(24.dp),
-                    tint = contentColor
-                )
-            }
-            Column(modifier = Modifier.weight(1f)) {
-                Text(
-                    text = stringResource(R.string.vpn_tunnel),
-                    style = MaterialTheme.typography.titleSmall,
-                    color = contentColor
-                )
-                Text(
-                    text = stringResource(statusTextRes),
-                    style = MaterialTheme.typography.bodyMedium,
-                    color = contentColor
-                )
-            }
-            Box(
-                modifier = Modifier.size(12.dp),
-                contentAlignment = Alignment.Center
+        AnimatedContent(
+            targetState = vpnData,
+            label = "vpnStatusAnim"
+        ) { targetData ->
+            Row(
+                modifier = Modifier
+                    .fillMaxWidth()
+                    .padding(16.dp),
+                verticalAlignment = Alignment.CenterVertically,
+                horizontalArrangement = Arrangement.spacedBy(12.dp)
             ) {
-                val indicatorColor = if (uiState.isVpnEnabled) statusColors.success else MaterialTheme.colorScheme.outline
-                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
-                    drawCircle(color = indicatorColor)
+                if (targetData.icon != null) {
+                    Icon(
+                        imageVector = targetData.icon,
+                        contentDescription = null,
+                        modifier = Modifier.size(24.dp),
+                        tint = contentColor
+                    )
+                }
+                Column(modifier = Modifier.weight(1f)) {
+                    Text(
+                        text = stringResource(R.string.vpn_tunnel),
+                        style = MaterialTheme.typography.titleSmall,
+                        color = contentColor
+                    )
+                    Text(
+                        text = stringResource(targetData.textRes),
+                        style = MaterialTheme.typography.bodyMedium,
+                        color = contentColor
+                    )
+                }
+                Box(
+                    modifier = Modifier.size(12.dp),
+                    contentAlignment = Alignment.Center
+                ) {
+                    val indicatorColor = if (targetData.containerColor == statusColors.success) statusColors.success else MaterialTheme.colorScheme.outline
+                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
+                        drawCircle(color = indicatorColor)
+                    }
                 }
             }
         }
PATCH_EOF
