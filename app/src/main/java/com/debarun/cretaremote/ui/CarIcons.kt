package com.debarun.cretaremote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A car seen from the side with its boot lid raised. Same shape as res/drawable/ic_shortcut_boot.xml. */
val CarBootIcon: ImageVector by lazy {
    ImageVector.Builder("CarBoot", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M1.5,15.5V12L4.2,9H16.4L20.5,12.6V15.5H19.1A3.1,3.1 0 0 0 12.9,15.5H9.3A3.1,3.1 0 0 0 3.1,15.5Z M5.6,10.4L4.2,12H9.4V10.4Z M10.8,10.4V12H15.6L14.4,10.4Z M16.4,9L18.4,9L21.6,3.4L19.6,2.4Z M3.9,16A2.3,2.3 0 1,0 8.5,16A2.3,2.3 0 1,0 3.9,16Z M13.7,16A2.3,2.3 0 1,0 18.3,16A2.3,2.3 0 1,0 13.7,16Z"),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black),
    ).build()
}
