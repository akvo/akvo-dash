import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { connect } from 'react-redux';
import { isEmpty, cloneDeep } from 'lodash';
import get from 'lodash/get';
import ShareEntity from '../components/modals/ShareEntity';
import * as actions from '../actions/dashboard';
import * as api from '../api';
import { fetchLibrary } from '../actions/library';
import { fetchDataset } from '../actions/dataset';
import aggregationOnlyVisualisationTypes from '../utilities/aggregationOnlyVisualisationTypes';
import LoadingSpinner from '../components/common/LoadingSpinner';
import { SAVE_COUNTDOWN_INTERVAL, SAVE_INITIAL_TIMEOUT } from '../constants/time';
import NavigationPrompt from '../components/common/NavigationPrompt';

const getEditingStatus = (location) => {
  const testString = 'create';

  return location.pathname.indexOf(testString) === -1;
};

const getLayoutObjectFromArray = (arr) => {
  const object = {};

  arr.forEach((item) => {
    const key = item.i;

    object[key] = item;
  });

  return object;
};

/* Get a pure representation of a dashboard from the container state */
const getDashboardFromState = (stateDashboard, isForEditor) => {
  const chosenLayoutRepresentation = isForEditor ?
        stateDashboard.layout : getLayoutObjectFromArray(stateDashboard.layout);

  const dashboard = Object.assign({}, stateDashboard, { layout: chosenLayoutRepresentation });

  return dashboard;
};

class Dashboard extends Component {

  constructor() {
    super();
    this.state = {
      dashboard: {
        type: 'dashboard',
        title: 'Untitled dashboard',
        entities: {},
        layout: [],
        id: null,
        created: null,
        modified: null,
        shareId: '',
      },
      isSavePending: null,
      isUnsavedChanges: null,
      isShareModalVisible: false,
      requestedDatasetIds: [],
      asyncComponents: null,
      aggregatedDatasets: {},
      timeToNextSave: SAVE_INITIAL_TIMEOUT,
      timeFromPreviousSave: 0,
    };
    this.loadDashboardIntoState = this.loadDashboardIntoState.bind(this);
    this.onAddVisualisation = this.onAddVisualisation.bind(this);
    this.updateLayout = this.updateLayout.bind(this);
    this.updateEntities = this.updateEntities.bind(this);
    this.onUpdateName = this.onUpdateName.bind(this);
    this.onSave = this.onSave.bind(this);
    this.onSaveFailure = this.onSaveFailure.bind(this);
    this.toggleShareDashboard = this.toggleShareDashboard.bind(this);
    this.handleDashboardAction = this.handleDashboardAction.bind(this);
    this.addDataToVisualisations = this.addDataToVisualisations.bind(this);
    this.handleFetchShareId = this.handleFetchShareId.bind(this);
    this.handleSetSharePassword = this.handleSetSharePassword.bind(this);
    this.handleToggleShareProtected = this.handleToggleShareProtected.bind(this);
  }

  componentWillMount() {
    const isEditingExistingDashboard = getEditingStatus(this.props.location);
    const isLibraryLoaded = !isEmpty(this.props.library.datasets);

    if (!isLibraryLoaded) {
      this.props.dispatch(fetchLibrary());
    }

    if (isEditingExistingDashboard) {
      this.setState({ isUnsavedChanges: false });
      const dashboardId = this.props.params.dashboardId;
      const existingDashboardLoaded =
        isLibraryLoaded && !isEmpty(this.props.library.dashboards[dashboardId].layout);

      if (!existingDashboardLoaded) {
        this.props.dispatch(actions.fetchDashboard(dashboardId));
      } else {
        this.loadDashboardIntoState(this.props.library.dashboards[dashboardId], this.props.library);
      }
    }
  }

  componentDidMount() {
    this.isMountedFlag = true;
    require.ensure(['../components/charts/VisualisationViewer'], () => {
      require.ensure([], () => {
        /* eslint-disable global-require */
        const DashboardEditor = require('../components/dashboard/DashboardEditor').default;
        const DashboardHeader = require('../components/dashboard/DashboardHeader').default;
        /* eslint-enable global-require */

        this.setState({
          asyncComponents: {
            DashboardEditor,
            DashboardHeader,
          },
        });
      }, 'Dashboard');
    }, 'VisualisationViewerPreload');
  }

  componentWillReceiveProps(nextProps) {
    const isEditingExistingDashboard = getEditingStatus(this.props.location);
    const dashboardAlreadyLoaded = this.state.dashboard.layout.length !== 0;
    const { dashboardId } = nextProps.params;

    if (
      (isEditingExistingDashboard && !dashboardAlreadyLoaded) ||
      get(this.state, 'dashboard.shareId') !== get(nextProps, `library.dashboards[${dashboardId}].shareId`) ||
      get(this.state, 'dashboard.protected') !== get(nextProps, `library.dashboards[${dashboardId}].protected`)
    ) {
      /* We need to load a dashboard, and we haven't loaded it yet. Check if nextProps has both i)
      /* the dashboard and ii) the visualisations the dashboard contains, then load the dashboard
      /* editor if both these conditions are true. */

      const dash = nextProps.library.dashboards[dashboardId];
      const haveDashboardData = Boolean(dash && dash.layout);

      if (haveDashboardData) {
        const dashboardEntities = Object.keys(dash.entities).map(key => dash.entities[key]);
        const dashboardHasVisualisations =
          dashboardEntities.some(entity => entity.type === 'visualisation');
        const libraryHasVisualisations = !isEmpty(nextProps.library.visualisations);

        if (dashboardHasVisualisations && !libraryHasVisualisations) {
          /* We don't yet have the visualisations necessary to display this dashboard. Do nothing.
          /* When the library API call returns and the visualisaitons are loaded,
          /* componentWillReceiveProps will be called again. */
          return;
        }
        this.loadDashboardIntoState(dash, nextProps.library);
      }
    }

    if (!this.props.params.dashboardId && dashboardId) {
      this.setState({
        isSavePending: false,
        isUnsavedChanges: false,
        dashboard: Object.assign({}, this.state.dashboard, { id: dashboardId }),
      });
    }
  }

  componentWillUnmount() {
    this.isMountedFlag = false;
  }

  onSaveFailure() {
    this.setState({
      timeToNextSave: this.state.timeToNextSave * 2,
      timeFromPreviousSave: 0,
      savingFailed: true,
    }, () => {
      this.saveInterval = setInterval(() => {
        const { timeFromPreviousSave, timeToNextSave } = this.state;
        if (timeToNextSave - timeFromPreviousSave > SAVE_COUNTDOWN_INTERVAL) {
          this.setState({ timeFromPreviousSave: timeFromPreviousSave + SAVE_COUNTDOWN_INTERVAL });
          return;
        }
        clearInterval(this.saveInterval);
      }, SAVE_COUNTDOWN_INTERVAL);
      setTimeout(() => {
        this.onSave();
      }, this.state.timeToNextSave);
    });
  }

  onSave() {
    const { dispatch, location } = this.props;
    const dashboard = getDashboardFromState(this.state.dashboard, false);
    const isEditingExistingDashboard = getEditingStatus(this.props.location);

    const handleResponse = (error) => {
      if (!this.isMountedFlag) {
        return;
      }
      if (error) {
        this.onSaveFailure();
        return;
      }
      this.setState({
        isUnsavedChanges: false,
        timeToNextSave: SAVE_INITIAL_TIMEOUT,
        timeFromPreviousSave: 0,
        savingFailed: false,
      });
    };

    if (isEditingExistingDashboard) {
      dispatch(actions.saveDashboardChanges(dashboard, handleResponse));
    } else if (!this.state.isSavePending) {
      this.setState({ isSavePending: true, isUnsavedChanges: false }, () => {
        dispatch(actions.createDashboard(dashboard, get(location, 'state.collectionId'), handleResponse));
      });
    } else {
      // Ignore save request until the first "create dashboard" request succeeeds
    }
  }

  onAddVisualisation(visualisation) {
    const { id, datasetId, spec } = visualisation;
    const vType = visualisation.visualisationType;

    if (aggregationOnlyVisualisationTypes.find(item => item === vType)) {
      /* Only fetch the aggregated data */
      let aggType;

      switch (vType) {
        case 'map':
          api.post('/api/visualisations/maps', visualisation)
            .then((response) => {
              if (response.status >= 200 && response.status < 300) {
                response
                  .json()
                  .then((json) => {
                    const change = {};
                    change[id] = json;
                    const aggregatedDatasets =
                      Object.assign({}, this.state.aggregatedDatasets, change);

                    const metadataChange = {};
                    metadataChange[id] = json;

                    const metadata =
                      Object.assign(
                        {},
                        this.state.metadata,
                        metadataChange
                      );

                    this.setState({ aggregatedDatasets, metadata });
                  });
              }
            });
          /* Maps hit a different endpoint than other aggregations, so bail out now */
          return;
        case 'pie':
        case 'donut':
          aggType = 'pie';
          break;
        case 'pivot table':
          aggType = 'pivot';
          break;
        default:
          break;
      }

      api.get(`/api/aggregation/${datasetId}/${aggType}`, {
        query: JSON.stringify(spec),
      }).then(response => response.json()).then((response) => {
        const change = {};
        change[id] = response;
        const aggregatedDatasets = Object.assign({}, this.state.aggregatedDatasets, change);
        this.setState({ aggregatedDatasets });
      });
    } else {
      /* Fetch the whole dataset */
      const datasetLoaded = this.props.library.datasets[datasetId].columns;
      const datasetRequested = this.state.requestedDatasetIds.some(dId => dId === datasetId);

      if (!datasetLoaded && !datasetRequested) {
        const newRequestedDatasetIds = this.state.requestedDatasetIds.slice(0);

        newRequestedDatasetIds.push(datasetId);
        this.setState({
          requestedDatasetIds: newRequestedDatasetIds,
        });
        this.props.dispatch(fetchDataset(datasetId));
      }
    }
  }

  onUpdateName(title) {
    const normalizedTitle = title || 'Untitled dashboard';
    const dashboard = Object.assign({}, this.state.dashboard, { title: normalizedTitle });
    this.setState({
      dashboard,
      isUnsavedChanges: true,
    }, () => {
      this.onSave();
    });
  }

  updateLayout(layout) {
    const clonedLayout = cloneDeep(layout);
    const dashboard = Object.assign({}, this.state.dashboard, { layout: clonedLayout });
    const oldLayout = this.state.dashboard.layout;
    const layoutChanged = layout.some((item) => {
      const oldItem = oldLayout.find(oi => oi.i === item.i);

      if (oldItem === undefined) {
        return true;
      }

      const positionChanged = Boolean(oldItem.w !== item.w ||
        oldItem.h !== item.h ||
        oldItem.x !== item.x ||
        oldItem.y !== item.y);

      if (positionChanged) {
        return true;
      }

      return false;
    });

    this.setState({
      dashboard,
      isUnsavedChanges: layoutChanged ? true : this.state.isUnsavedChanges,
    }, () => {
      if (this.state.isUnsavedChanges) {
        this.onSave();
      }
    });
  }

  updateEntities(entities) {
    const dashboard = Object.assign({}, this.state.dashboard, { entities });
    this.setState({
      dashboard,
      isUnsavedChanges: true,
    });
  }

  handleDashboardAction(action) {
    switch (action) {
      case 'share':
        this.toggleShareDashboard();
        break;
      default:
        throw new Error(`Action ${action} not yet implemented`);
    }
  }

  handleFetchShareId() {
    const dashboard = getDashboardFromState(this.state.dashboard, true);
    this.props.dispatch(actions.fetchShareId(dashboard.id));
  }

  handleSetSharePassword(password) {
    if (!password) {
      this.setState({ passwordAlert: { message: 'Please enter a password.', type: 'danger' } });
      return;
    }
    const dashboard = getDashboardFromState(this.state.dashboard, true);
    this.setState({ passwordAlert: null });
    this.props.dispatch(actions.setShareProtection(
      dashboard.shareId,
      { password, protected: Boolean(password.length) },
      (error) => {
        const message = get(error, 'message');
        if (message) {
          this.setState({ passwordAlert: { message, type: 'danger' } });
          return;
        }
        this.setState({ passwordAlert: { message: 'Saved successfully.' } });
      }
    ));
  }

  handleToggleShareProtected(isProtected) {
    this.setState({ passwordAlert: null });
    const dashboard = getDashboardFromState(this.state.dashboard, true);
    this.props.dispatch(actions.setShareProtection(dashboard.shareId, { protected: isProtected }));
  }

  toggleShareDashboard() {
    this.setState({
      isShareModalVisible: !this.state.isShareModalVisible,
    });
  }

  loadDashboardIntoState(dash, library) {
    /* Put the dashboard into component state so it is fed to the DashboardEditor */
    const dashboard = Object.assign({}, dash,
      { layout: Object.keys(dash.layout).map(key => dash.layout[key]) }
    );
    this.setState({ dashboard });

    /* Load each unique dataset referenced by visualisations in the dashboard. Note - Even though
    /* onAddVisualisation also checks to see if a datasetId has already been requested, setState is
    /* not synchronous and is too slow here, hence the extra check */
    const requestedDatasetIds = this.state.requestedDatasetIds.slice(0);

    Object.keys(dash.entities).forEach((key) => {
      const entity = dash.entities[key];
      const isVisualisation = entity.type === 'visualisation';

      if (isVisualisation) {
        const visualisation = library.visualisations[key];

        if (aggregationOnlyVisualisationTypes.some(type =>
          type === visualisation.visualisationType)) {
          const alreadyProcessed = Boolean(visualisation.data);

          if (!alreadyProcessed) {
            this.onAddVisualisation(visualisation);
          }
        } else {
          const datasetId = visualisation.datasetId;
          const alreadyProcessed = requestedDatasetIds.some(id => id === datasetId);

          if (!alreadyProcessed) {
            requestedDatasetIds.push(datasetId);
            this.onAddVisualisation(visualisation);
          }
        }
      }
    });
  }

  addDataToVisualisations(visualisations) {
    const out = {};

    Object.keys(visualisations).forEach((key) => {
      if (this.state.aggregatedDatasets[key]) {
        if (visualisations[key].visualisationType === 'map') {
          const { tenantDB, layerGroupId, metadata } = this.state.aggregatedDatasets[key];
          out[key] = Object.assign(
            {},
            visualisations[key],
            {
              tenantDB,
              layerGroupId,
              metadata,
            },
            {
              spec: Object.assign(
                {},
                visualisations[key].spec,
                {
                  layers: visualisations[key].spec.layers.map((item, idx) => {
                    if (idx === 0 && metadata && metadata.pointColorMapping) {
                      return Object.assign(
                        {},
                        item,
                        { pointColorMapping: metadata.pointColorMapping }
                      );
                    }
                    return item;
                  }),
                }
              ),
            }
          );
        } else {
          out[key] = Object.assign(
            {},
            visualisations[key],
            { data: this.state.aggregatedDatasets[key] }
          );
        }
      } else {
        out[key] = visualisations[key];
      }
    });
    return out;
  }

  render() {
    if (!this.state.asyncComponents) {
      return <LoadingSpinner />;
    }
    const { DashboardHeader, DashboardEditor } = this.state.asyncComponents;
    const dashboard = getDashboardFromState(this.state.dashboard, true);

    return (
      <NavigationPrompt shouldPrompt={this.state.savingFailed}>
        <div className="Dashboard">
          <DashboardHeader
            title={dashboard.title}
            isUnsavedChanges={this.state.isUnsavedChanges}
            onDashboardAction={this.handleDashboardAction}
            onChangeTitle={this.onUpdateName}
            onBeginEditTitle={() => this.setState({ isUnsavedChanges: true })}
            onSaveDashboard={this.onSave}
            savingFailed={this.state.savingFailed}
            timeToNextSave={this.state.timeToNextSave - this.state.timeFromPreviousSave}
          />
          <DashboardEditor
            dashboard={dashboard}
            datasets={this.props.library.datasets}
            visualisations={this.addDataToVisualisations(this.props.library.visualisations)}
            metadata={this.state.metadata}
            onAddVisualisation={this.onAddVisualisation}
            onSave={this.onSave}
            onUpdateLayout={this.updateLayout}
            onUpdateEntities={this.updateEntities}
            onUpdateName={this.onUpdateName}
          />
          <ShareEntity
            isOpen={this.state.isShareModalVisible}
            onClose={this.toggleShareDashboard}
            title={dashboard.title}
            shareId={get(this.state, 'dashboard.shareId')}
            protected={get(this.state, 'dashboard.protected')}
            type={dashboard.type}
            canSetPrivacy
            onSetPassword={this.handleSetSharePassword}
            onFetchShareId={this.handleFetchShareId}
            onToggleProtected={this.handleToggleShareProtected}
            alert={this.state.passwordAlert}
          />
        </div>
      </NavigationPrompt>
    );
  }
}

Dashboard.propTypes = {
  dispatch: PropTypes.func.isRequired,
  library: PropTypes.object.isRequired,
  location: PropTypes.object.isRequired,
  params: PropTypes.object,
};

export default connect(state => state)(Dashboard);
