import React from 'react';
import { render } from 'react-dom';
import Root from './containers/Root';
import configureStore from './store/configureStore';
import keycloak from './auth';

function initAuthenticated(profile) {
  const initialState = {
    user: {
      name: profile.username,
      organization: 'Akvo DASH',
    },
  };

  const store = configureStore(initialState);
  const rootElement = document.querySelector('#root');
  render(<Root store={store}/>, rootElement);
}

function initNotAuthenticated() {
  document.querySelector('#root').innerHTML = 'Authentication required.';
}

keycloak.init({ onLoad: 'login-required' }).success((authenticated) => {
  if (authenticated) {
    keycloak.loadUserProfile().success((profile) => {
      initAuthenticated(profile);
    }).error(() => {
      initNotAuthenticated();
    });
  } else {
    initNotAuthenticated();
  }
}).error(() => {
  initNotAuthenticated();
});
