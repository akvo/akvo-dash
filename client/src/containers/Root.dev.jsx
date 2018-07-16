import React from 'react';
import PropTypes from 'prop-types';
import { Provider } from 'react-redux';
import App from './App';
import DevTools from './DevTools';
import PrintProvider from './PrintProvider';

export default function Root({ store, history }) {
  return (
    <Provider store={store}>
      <PrintProvider>
        <div>
          <App history={history} />
          <DevTools />
        </div>
      </PrintProvider>
    </Provider>
  );
}

Root.propTypes = {
  store: PropTypes.object.isRequired,
  history: PropTypes.object.isRequired,
};
