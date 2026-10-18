import {
  TurboModuleRegistry,
  type CodegenTypes,
  type TurboModule,
} from 'react-native';

export interface Spec extends TurboModule {
  ready(config: Object): Promise<Object>;
  start(): Promise<Object>;
  stop(): Promise<Object>;
  changePace(isMoving: boolean): Promise<Object>;
  getState(): Promise<Object>;
  getCount(): Promise<number>;
  getLocations(): Promise<Object[]>;
  destroyLocations(): Promise<boolean>;

  addGeofence(geofence: Object): Promise<boolean>;
  addGeofences(geofences: Object[]): Promise<boolean>;
  removeGeofence(identifier: string): Promise<boolean>;
  removeGeofences(identifiers: string[]): Promise<boolean>;
  getGeofences(): Promise<Object[]>;
  getGeofence(identifier: string): Promise<Object>;
  geofenceExists(identifier: string): Promise<boolean>;

  requestPermission(): Promise<Object>;
  getProviderState(): Promise<Object>;
  getCapabilities(): Promise<Object>;

  readonly onEvent: CodegenTypes.EventEmitter<Object>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('BgGeolocation');
