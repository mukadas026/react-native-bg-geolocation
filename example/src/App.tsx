import { useCallback, useEffect, useState } from 'react';
import {
  Button,
  NativeModules,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import BackgroundGeolocation, { type State } from 'react-native-bg-geolocation';

const AUTO_START = false;
const AUTO_DEMO = false;
const DEMO_GEOFENCE = {
  identifier: 'demo',
  latitude: 5.605,
  longitude: -0.187,
  radius: 60,
};

const UPLOAD_URL = 'http://localhost:8080/locations';

type KioskStatus = 'unknown' | 'unsupported' | 'not-owner' | 'on' | 'off';

const Kiosk = Platform.OS === 'android' ? NativeModules.Kiosk : undefined;

export default function App() {
  const [state, setState] = useState<State | null>(null);
  const [log, setLog] = useState<string[]>([]);
  const [kiosk, setKiosk] = useState<KioskStatus>('unknown');

  const append = useCallback((line: string) => {
    setLog((previous) =>
      [`${new Date().toLocaleTimeString()} ${line}`, ...previous].slice(0, 60)
    );
  }, []);

  const refresh = useCallback(async () => {
    setState(await BackgroundGeolocation.getState());
  }, []);

  useEffect(() => {
    if (!Kiosk) {
      setKiosk('unsupported');
      return;
    }
    Kiosk.isDeviceOwner().then(
      async (isOwner: boolean) => {
        if (!isOwner) {
          setKiosk('not-owner');
          return;
        }
        await Kiosk.enableKioskMode();
        setKiosk('on');
      },
      () => setKiosk('unknown')
    );
  }, []);

  useEffect(() => {
    const subscriptions = [
      BackgroundGeolocation.onLocation((location) => {
        const { latitude, longitude } = location.coords;
        append(`location ${latitude.toFixed(5)}, ${longitude.toFixed(5)}`);
        refresh();
      }),
      BackgroundGeolocation.onMotionChange((event) => {
        append(`motionchange isMoving=${event.isMoving}`);
        refresh();
      }),
      BackgroundGeolocation.onEnabledChange((event) => {
        append(`enabledchange enabled=${event.enabled}`);
        refresh();
      }),
      BackgroundGeolocation.onBufferOverflow((event) =>
        append(`buffer-overflow dropped=${event.dropped.length}`)
      ),
      BackgroundGeolocation.onGeofence((event) =>
        append(`geofence ${event.identifier} ${event.action}`)
      ),
      BackgroundGeolocation.onProviderChange((event) =>
        append(`provider ${event.authorization} enabled=${event.enabled}`)
      ),
      BackgroundGeolocation.onMotionSource((event) =>
        append(`motion source ${event.source}`)
      ),
      BackgroundGeolocation.onError((event) =>
        append(`error ${event.message}`)
      ),
    ];

    BackgroundGeolocation.ready({
      distanceFilter: 10,
      stopTimeout: 1,
      stationaryRadius: 25,
      url: UPLOAD_URL,
      notificationTitle: 'bg-geolocation example',
      geofenceCheckInterval: 30,
    }).then(
      async (ready) => {
        setState(ready);
        if (AUTO_START) await BackgroundGeolocation.start();
        if (AUTO_DEMO) {
          await BackgroundGeolocation.start();
          await BackgroundGeolocation.addGeofence(DEMO_GEOFENCE);
          const capabilities = await BackgroundGeolocation.getCapabilities();
          const provider = await BackgroundGeolocation.getProviderState();
          append(`capabilities ${JSON.stringify(capabilities)}`);
          append(`provider ${JSON.stringify(provider)}`);
          append(
            `geofences ${(await BackgroundGeolocation.getGeofences()).length}`
          );
        }
      },
      (error: Error) => append(`ready failed: ${error.message}`)
    );

    return () => subscriptions.forEach((subscription) => subscription.remove());
  }, [append, refresh]);

  const run = (label: string, action: () => Promise<unknown>) => async () => {
    try {
      await action();
      await refresh();
    } catch (error) {
      append(`${label} failed: ${(error as Error).message}`);
    }
  };

  const addDemoGeofence = run('geofence', async () => {
    await BackgroundGeolocation.addGeofence(DEMO_GEOFENCE);
    append(`geofence ${DEMO_GEOFENCE.identifier} added`);
  });

  const askPermission = run('permission', async () => {
    const provider = await BackgroundGeolocation.requestPermission();
    const capabilities = await BackgroundGeolocation.getCapabilities();
    append(
      `permission ${provider.authorization}/${provider.accuracy} ` +
        `engine=${capabilities.engine} geofenceLimit=${capabilities.geofenceLimit}`
    );
  });

  const toggleKiosk = async () => {
    if (!Kiosk) return;
    try {
      if (kiosk === 'on') {
        await Kiosk.disableKioskMode();
        setKiosk('off');
      } else {
        await Kiosk.enableKioskMode();
        setKiosk('on');
      }
    } catch (error) {
      append(`kiosk failed: ${(error as Error).message}`);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <Text style={[styles.text, styles.title]}>bg-geolocation</Text>
      <Text testID="state" style={styles.text}>
        {state
          ? `${state.trackingState} · buffered ${state.count}`
          : 'not ready'}
      </Text>
      <Text testID="kiosk" style={styles.text}>
        kiosk: {kiosk}
      </Text>
      <View style={styles.row}>
        <Button
          title="Start"
          onPress={run('start', BackgroundGeolocation.start)}
        />
        <Button
          title="Stop"
          onPress={run('stop', BackgroundGeolocation.stop)}
        />
        <Button
          title={state?.isMoving ? 'Still' : 'Move'}
          onPress={run('changePace', () =>
            BackgroundGeolocation.changePace(!state?.isMoving)
          )}
        />
        <Button
          title={kiosk === 'on' ? 'Kiosk off' : 'Kiosk on'}
          onPress={toggleKiosk}
          disabled={
            kiosk === 'not-owner' ||
            kiosk === 'unknown' ||
            kiosk === 'unsupported'
          }
        />
        <Button title="Permission" onPress={askPermission} />
        <Button title="Geofence" onPress={addDemoGeofence} />
        <Button
          title="Clear"
          onPress={run('clear', BackgroundGeolocation.destroyLocations)}
        />
      </View>
      <ScrollView style={styles.log}>
        {log.map((line, index) => (
          <Text key={`${index}-${line}`} style={[styles.text, styles.logLine]}>
            {line}
          </Text>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 12, backgroundColor: '#fff' },
  text: { color: '#111' },
  title: { fontSize: 20, fontWeight: '600' },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  log: { flex: 1 },
  logLine: { fontFamily: 'monospace', fontSize: 12 },
});
