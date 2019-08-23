/* eslint-disable no-underscore-dangle */
import queryString from 'querystringify';

export function token(keycloak) {
  return new Promise(resolve =>
    keycloak
      .updateToken()
      .success(() => resolve(keycloak.token))
      .error(() => {
        // Redirect to login page
        keycloak.login();
      })
  );
}

export function login(keycloak) {
  if (keycloak == null) {
    throw new Error('Keycloak not initialized');
  }
  return keycloak.login();
}
// eslint-disable-next-line no-unused-vars
export function logout(keycloak) {
  throw new Error('Keycloak dont have this logout functionality');
}

export function init(env, keycloak) {
  return new Promise((resolve, reject) => {
    const queryParams = queryString.parse(location.search);
    keycloak
    .init({
      onLoad: 'login-required',
      checkLoginIframe: false,
      token: queryParams.token,
    })
    .success((authenticated) => {
      if (authenticated) {
        keycloak
          .loadUserProfile()
          .success((profile) => {
            resolve({
              profile: Object.assign({}, profile, {
                admin: keycloak.hasRealmRole(`akvo:lumen:${env.tenant}:admin`),
              }),
              env,
            });
          })
          .error(() => {
            reject(new Error('Could not load user profile'));
          });
      } else {
        reject(new Error('Could not authenticate'));
      }
    })
    .error(() => {
      reject(new Error('Login attempt failed'));
    });
  });
}
