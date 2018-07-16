import React from 'react';
import PropTypes from 'prop-types';
import { Provider } from 'react-redux';

import App from './App';
import PrintProvider from './PrintProvider';

export default function Root({ store, history }) {
  return (
    <Provider store={store}>
      <PrintProvider>
        <App history={history} />
      </PrintProvider>
    </Provider>
  );
}

Root.propTypes = {
  store: PropTypes.object.isRequired,
  history: PropTypes.object.isRequired,
};
