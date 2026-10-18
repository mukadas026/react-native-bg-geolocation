import { withInfoPlist, type ConfigPlugin } from '@expo/config-plugins';

export type Props = {
  iosAlwaysUsageDescription?: string;
  iosWhenInUseUsageDescription?: string;
};

const DEFAULT_ALWAYS_USAGE =
  'This app tracks your location in the background, including when it is closed, to record where this device has been.';
const DEFAULT_WHEN_IN_USE_USAGE =
  'This app uses your location to record where this device has been.';

type InfoPlist = Record<string, unknown>;

export function applyInfoPlist(
  infoPlist: InfoPlist,
  props: Props = {}
): InfoPlist {
  const existingModes = Array.isArray(infoPlist.UIBackgroundModes)
    ? (infoPlist.UIBackgroundModes as string[])
    : [];

  return {
    ...infoPlist,
    UIBackgroundModes: Array.from(new Set([...existingModes, 'location'])),
    NSLocationAlwaysAndWhenInUseUsageDescription:
      props.iosAlwaysUsageDescription ??
      infoPlist.NSLocationAlwaysAndWhenInUseUsageDescription ??
      DEFAULT_ALWAYS_USAGE,
    NSLocationWhenInUseUsageDescription:
      props.iosWhenInUseUsageDescription ??
      infoPlist.NSLocationWhenInUseUsageDescription ??
      DEFAULT_WHEN_IN_USE_USAGE,
  };
}

const withBgGeolocation: ConfigPlugin<Props | void> = (config, props) =>
  withInfoPlist(config, (modConfig) => {
    modConfig.modResults = applyInfoPlist(
      modConfig.modResults,
      props ?? {}
    ) as typeof modConfig.modResults;
    return modConfig;
  });

export default withBgGeolocation;
