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

  readonly onEvent: CodegenTypes.EventEmitter<Object>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('BgGeolocation');
