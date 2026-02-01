import { ViewProps, requireNativeComponent } from 'react-native';
import React from 'react';

const LiquidGlassRangeSliderViewNative = requireNativeComponent('LiquidGlassRangeSliderView');

interface LiquidGlassViewProps extends ViewProps {
  refraction?: number;
  magnification?: number;
  offsetX?: number;
  offsetY?: number;
}

export const LiquidGlassView = (props: LiquidGlassViewProps) => {
  return <LiquidGlassRangeSliderViewNative {...props} />;
};
