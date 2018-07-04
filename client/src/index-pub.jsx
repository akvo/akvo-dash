import React, { Component } from 'react';
import { render } from 'react-dom';
import { Provider } from 'react-redux';
import Immutable from 'immutable';
import fetch from 'isomorphic-fetch';
import IntlWrapper from './containers/IntlWrapper';
import VisualisationViewerContainer from './components/visualisation/VisualisationViewerContainer';
import DashboardViewer from './components/dashboard/DashboardViewer';
import LumenBranding from './components/common/LumenBranding';
import ErrorScreen from './components/common/ErrorScreen';
import polyfill from './polyfill/polyfill';
import configureStore from './store/configureStore';
import { init as initAnalytics, trackPageView } from './utilities/analytics';
import * as auth from './auth';

require('./styles/reset.global.scss');
require('./styles/style.global.scss');

const rootElement = document.querySelector('#root');

function renderSuccessfulShare(data, initialState) {
  const datasets = data.datasets;
  const immutableDatasets = {};

  // Transform datasets into immutable objects
  if (datasets != null) {
    Object.keys(datasets).forEach((key) => {
      const dataset = Immutable.fromJS(datasets[key]);
      immutableDatasets[key] = dataset;
    });
  }

  initAnalytics(initialState);

  const entity = data.dashboards ?
    data.dashboards[data.dashboardId] :
    data.visualisations[data.visualisationId];

  const entityType = data.dashboards ? 'dashboard' : 'visualisation';

  trackPageView(`Share ${entityType}: ${entity.name || `Untitled ${entityType}`}`);

  render(
    <Provider store={configureStore()}>
      <IntlWrapper>
        <div className="viewer">
          {data.dashboards ? (
            <DashboardViewer
              dashboard={data.dashboards[data.dashboardId]}
              visualisations={data.visualisations}
              datasets={immutableDatasets}
              metadata={data.metadata ? data.metadata : null}
            />
          ) : (
            <VisualisationViewerContainer
              visualisation={data.visualisations[data.visualisationId]}
              metadata={data.metadata ? data.metadata[data.visualisationId] : null}
              datasets={immutableDatasets}
            />
          )}
          <LumenBranding
            size={data.dashboards ? 'large' : 'small'}
          />
        </div>
      </IntlWrapper>
    </Provider>,
    rootElement
  );
}

function renderNoSuchShare() {
  render(
    <div>No such public dashboard or visualisation</div>,
    rootElement
  );
}

const pathMatch = window.location.pathname.match(/^\/s\/(.*)/);
const shareId = pathMatch != null ? pathMatch[1] : null;
let hasSubmitted = false;

const fetchData = (password = undefined, callback = () => {}) => {
  auth.initPublic()
    .then(({ env }) => {
      fetch(`/share/${shareId}`, { headers: { 'X-Password': password } })
        .then((response) => {
          if (response.status === 403) {
            renderPrivacyGate(); // eslint-disable-line
            callback();
            return null;
          }
          return response.json();
        })
        .then((data) => {
          if (data) renderSuccessfulShare(data, { env });
        })
        .catch((error) => {
          renderNoSuchShare();
          throw error;
        });
    });
};

class PrivacyGate extends Component {
  constructor(props) {
    super(props);
    this.handleSubmit = this.handleSubmit.bind(this);
  }
  componentDidMount() {
    this.passwordInput.focus();
  }
  handleSubmit() {
    hasSubmitted = true;
    fetchData(this.state.password, () => this.forceUpdate());
  }
  render() {
    return (
      <ErrorScreen
        code={403}
        codeVisible={false}
        title="A password is required to view this visualisation/dashboard"
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            this.handleSubmit();
          }}
        >
          {hasSubmitted && (
            <div className="alert alert-danger">
              Password incorrect
            </div>
          )}
          <div className="clearfix" />
          <input
            ref={(c) => { this.passwordInput = c; }}
            onChange={({ target: { value } }) => {
              this.setState({ password: value });
            }}
            type="password"
            placeholder="Password"
          />
          <a
            className="submitButton"
            onClick={this.handleSubmit}
          >
            Submit
          </a>
        </form>
      </ErrorScreen>
    );
  }
}

function renderPrivacyGate() {
  render(<PrivacyGate />, rootElement);
}

if (shareId != null) {
  polyfill(fetchData);
}
