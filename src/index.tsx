import NativeBgGeolocation from './NativeBgGeolocation';

export type Coords = {
  latitude: number;
  longitude: number;
  accuracy?: number;
  speed?: number;
  heading?: number;
  altitude?: number;
};

export type Location = {
  uuid: string;
  timestamp?: number;
  coords: Coords;
};

export type MotionChangeEvent = { isMoving: boolean };
export type EnabledChangeEvent = { enabled: boolean };
export type BufferOverflowEvent = { dropped: string[] };

export type Config = {
  distanceFilter?: number;
  stopTimeout?: number;
  stationaryRadius?: number;
  autoSync?: boolean;
  maxBatchSize?: number;
  syncRetryBaseDelay?: number;
  syncRetryMaxDelay?: number;
  maxRecordsToPersist?: number;
  url?: string;
  headers?: Record<string, string>;
  notificationTitle?: string;
  notificationText?: string;
  motionPollInterval?: number;
  useMotionSensors?: boolean;
};

export type State = {
  enabled: boolean;
  trackingState: 'stopped' | 'moving' | 'stationary';
  isMoving: boolean;
  count: number;
  motionSource?:
    | 'significant-motion'
    | 'step-detector'
    | 'accelerometer'
    | 'gps-poll'
    | null;
};

export type Subscription = { remove(): void };

type Listener<T> = (payload: T) => void;

function subscribe<T>(name: string, listener: Listener<T>): Subscription {
  return NativeBgGeolocation.onEvent((event) => {
    const { name: eventName, ...payload } = event as { name: string };
    if (eventName === name) {
      listener(payload as T);
    }
  });
}

const BackgroundGeolocation = {
  ready: (config: Config) =>
    NativeBgGeolocation.ready(config) as Promise<State>,
  start: () => NativeBgGeolocation.start() as Promise<State>,
  stop: () => NativeBgGeolocation.stop() as Promise<State>,
  changePace: (isMoving: boolean) =>
    NativeBgGeolocation.changePace(isMoving) as Promise<State>,
  getState: () => NativeBgGeolocation.getState() as Promise<State>,
  getCount: () => NativeBgGeolocation.getCount(),
  getLocations: () => NativeBgGeolocation.getLocations() as Promise<Location[]>,
  destroyLocations: () => NativeBgGeolocation.destroyLocations(),

  onLocation: (listener: Listener<Location>) => subscribe('location', listener),
  onMotionChange: (listener: Listener<MotionChangeEvent>) =>
    subscribe('motionchange', listener),
  onEnabledChange: (listener: Listener<EnabledChangeEvent>) =>
    subscribe('enabledchange', listener),
  onBufferOverflow: (listener: Listener<BufferOverflowEvent>) =>
    subscribe('buffer-overflow', listener),
  onMotionSource: (listener: Listener<{ source: string }>) =>
    subscribe('motionsource', listener),
  onError: (listener: Listener<{ message: string }>) =>
    subscribe('error', listener),
};

export default BackgroundGeolocation;
