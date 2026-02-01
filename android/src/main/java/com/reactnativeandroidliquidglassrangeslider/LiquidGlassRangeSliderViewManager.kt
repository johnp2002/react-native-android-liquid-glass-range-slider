package com.reactnativeandroidliquidglassrangeslider

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

class LiquidGlassRangeSliderViewManager : SimpleViewManager<LiquidGlassRangeSliderView>() {
    override fun getName() = "LiquidGlassRangeSliderView"

    override fun createViewInstance(reactContext: ThemedReactContext): LiquidGlassRangeSliderView {
        return LiquidGlassRangeSliderView(reactContext)
    }

    @com.facebook.react.uimanager.annotations.ReactProp(name = "refraction")
    fun setRefraction(view: LiquidGlassRangeSliderView, refraction: Float) {
        view.refraction = refraction
    }

    @com.facebook.react.uimanager.annotations.ReactProp(name = "magnification")
    fun setMagnification(view: LiquidGlassRangeSliderView, magnification: Float) {
        view.magnification = magnification
    }

    @com.facebook.react.uimanager.annotations.ReactProp(name = "offsetX")
    fun setOffsetX(view: LiquidGlassRangeSliderView, offsetX: Float) {
        view.offsetX = offsetX
    }

    @com.facebook.react.uimanager.annotations.ReactProp(name = "offsetY")
    fun setOffsetY(view: LiquidGlassRangeSliderView, offsetY: Float) {
        view.offsetY = offsetY
    }
}
