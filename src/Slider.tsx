import { View, StyleSheet, LayoutChangeEvent } from 'react-native'
import React, { useState } from 'react'
import {
  Gesture,
  GestureDetector,
} from 'react-native-gesture-handler'
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated'

import { LiquidGlassView } from './LiquidGlassView'

interface SliderProps {
  thumbWidth?: number
  thumbHeight?: number
  trackHeight?: number
  refraction?: number;
  magnification?: number;
  offsetX?: number;
  offsetY?: number;
  color?: string;
}

const Slider = ({
  thumbWidth = 60,
  thumbHeight = 40,
  trackHeight = 10,
  refraction,
  magnification,
  offsetX,
  offsetY,
  color = '#37cf18',
}: SliderProps) => {
  const [sliderWidth, setSliderWidth] = useState(0)
  const translateX = useSharedValue(0)
  const context = useSharedValue(0)
  const isActive = useSharedValue(0)

  const onLayout = (event: LayoutChangeEvent) => {
    const { width } = event.nativeEvent.layout
    setSliderWidth(width)
  }

  const pan = Gesture.Pan()
    .onBegin(() => {
      isActive.value = withSpring(1)
    })
    .onStart(() => {
      context.value = translateX.value
    })
    .onUpdate((event) => {
      let newValue = context.value + event.translationX
      if (newValue < 0) newValue = 0
      if (newValue > sliderWidth) newValue = sliderWidth
      translateX.value = newValue
    })
    .onFinalize(() => {
      isActive.value = withSpring(0)
    })

  const rStyle = useAnimatedStyle(() => {
    const scale = 0.8 + isActive.value * 0.3 // Interpolate 0.8 to 1.1
    return {
      transform: [
        { translateX: translateX.value },
        { scale: scale }
      ],
    }
  })

  const rGlassStyle = useAnimatedStyle(() => {
    return {
      opacity: isActive.value,
    }
  })

  const rSolidStyle = useAnimatedStyle(() => {
    return {
      opacity: 1 - isActive.value,
      backgroundColor: '#FFFFFF',
      borderRadius: thumbHeight / 2,
      ...StyleSheet.absoluteFillObject,
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 2 },
      shadowOpacity: 0.2,
      shadowRadius: 5,
      elevation: 4,
    }
  })

  // Width of the green track needs to follow the thumb
  const rTrackStyle = useAnimatedStyle(() => {
     return {
        width: translateX.value
     }
  })

  return (
    <View 
      onLayout={onLayout}
      style={[styles.trackContainer, { height: trackHeight, borderRadius: trackHeight / 2 }]}
    >
        {/* Track Background */}
        <View style={[styles.track, { borderRadius: trackHeight / 2 }]} />

        {/* Active Track (Green) */}
        <Animated.View style={[styles.activeTrack, { height: trackHeight, borderRadius: trackHeight / 2, backgroundColor: color }, rTrackStyle]} />
        
        {/* Thumb */}
        <GestureDetector gesture={pan}>
          <Animated.View style={[
            styles.thumb, 
            { 
              width: thumbWidth, 
              height: thumbHeight, 
              left: -thumbWidth / 2,
              top: (trackHeight - thumbHeight) / 2,
            }, 
            rStyle
          ]}>
             <Animated.View style={rSolidStyle} />
             <Animated.View style={[{ flex: 1 }, rGlassStyle]}>
                <LiquidGlassView 
                  style={{ flex: 1 }} 
                  refraction={refraction}
                  magnification={magnification}
                  offsetX={offsetX}
                  offsetY={offsetY}
                />
             </Animated.View>
          </Animated.View>
        </GestureDetector>
    </View>
  )
}

const styles = StyleSheet.create({
  trackContainer: {
    width: '100%',
    backgroundColor: '#9e9e9e89',
    justifyContent: 'center',
  },
  track: {
    ...StyleSheet.absoluteFillObject,
  },
  activeTrack: {
    // backgroundColor: '#37cf18', // Moved to inline style for dynamic color
    position: 'absolute',
    left: 0,
  },
  thumb: {
    position: 'absolute',
  },
})

export default Slider
