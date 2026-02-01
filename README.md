# react-native-android-liquid-glass-range-slider

A React Native slider component with a unique Android "Liquid Glass" effect using OpenGL.

![Demo](demo.gif)

## Installation

```sh
npm install react-native-android-liquid-glass-range-slider
```

### Peer Dependencies

This library requires the following peer dependencies:

*   `react-native`
*   `react-native-reanimated`
*   `react-native-gesture-handler`

## Usage

```tsx
import { Slider } from 'react-native-android-liquid-glass-range-slider';

// ...

<Slider 
  thumbWidth={60}
  thumbHeight={40}
  trackHeight={10}
  // Optional liquid glass props
  refraction={0.25}
  offsetX={0}
  color="#37cf18"
  onValueChange={(val) => {
    console.log('Slider Progress:', val); // 0 to 1
  }}
/>
```

## Props

All props are optional.

### Layout Props

| Prop | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `thumbWidth` | `number` | `60` | Width of the slider thumb. |
| `thumbHeight` | `number` | `40` | Height of the slider thumb. |
| `trackHeight` | `number` | `10` | Height of the slider track. |
| `color` | `string` | `#37cf18` | Color of the active track. |
| `onValueChange` | `(value: number) => void` | `-` | Callback fired when the slider value changes. Returns normalized value `0` to `1`. |
| `refraction` | `number` | `Native Default` | Controls the intensity of the refraction effect. |
| `magnification` | `number` | `Native Default` | Controls the zoom level of the background sample. |
| `offsetX` | `number` | `Native Default` | Horizontal offset for the glass effect. |
| `offsetY` | `number` | `Native Default` | Vertical offset for the glass effect. |
