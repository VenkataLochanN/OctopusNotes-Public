package com.lochan.octopusnotes

import android.app.Application
import com.google.android.material.color.DynamicColors

class OctopusNotesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
