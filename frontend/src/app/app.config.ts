import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { TitleStrategy, provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { I18nTitleStrategy } from './core/i18n/title-strategy';
import { authInterceptor, errorInterceptor } from './core/interceptors';
import { mockBackendInterceptor } from './core/mock/mock-backend.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    // route titles are dictionary keys, written in the console language and rewritten on a language switch
    { provide: TitleStrategy, useClass: I18nTitleStrategy },
    // order matters: auth -> error mapping -> (mock backend | network)
    provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor])),
  ],
};
