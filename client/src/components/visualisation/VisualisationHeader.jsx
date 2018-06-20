import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { FormattedMessage, intlShape, injectIntl } from 'react-intl';
import EntityTypeHeader from '../entity-editor/EntityTypeHeader';

class VisualisationHeader extends Component {

  constructor() {
    super();
    this.getActionButtons = this.getActionButtons.bind(this);
  }

  getActionButtons(isUnsavedChanges) {
    const { onVisualisationAction } = this.props;
    const save = {
      buttonText: <FormattedMessage id="save" />,
      primary: true,
      onClick: () => {
        this.props.onSaveVisualisation();
      },
      customClass: 'primaryButton',
      props: {
        'data-test-id': 'save-changes',
      },
    };
    const user = {
      buttonText: <FormattedMessage id="user" />,
      customClass: 'notImplemented',
    };
    const download = {
      buttonText: <FormattedMessage id="download" />,
      customClass: 'notImplemented',
    };
    const disableShare = isUnsavedChanges || isUnsavedChanges == null;
    const share = {
      buttonText: <FormattedMessage id="share" />,
      onClick: () => onVisualisationAction('share'),
      disabled: disableShare,
      tooltipId: disableShare ? 'save_your_visualisation_before_sharing' : null,
    };
    const overflow = {
      buttonText: <FormattedMessage id="overflow" />,
      customClass: 'notImplemented',
    };

    const result = [
      user,
      download,
      share,
      overflow,
    ];

    if (this.props.savingFailed) result.unshift(save);

    return result;
  }

  render() {
    const {
      visualisation,
      onChangeTitle,
      onBeginEditTitle,
      isUnsavedChanges,
      savingFailed,
      timeToNextSave,
      intl,
    } = this.props;

    const actionButtons = this.getActionButtons(isUnsavedChanges);
    let saveStatusId;

    switch (isUnsavedChanges) {
      case false:
        saveStatusId = 'all_changes_saved';
        break;
      case true:
        saveStatusId = 'unsaved_changes';
        break;
      default:
        saveStatusId = null;
    }

    if (savingFailed && timeToNextSave) {
      saveStatusId = 'saving_failed_countdown';
    }

    return (
      <EntityTypeHeader
        title={visualisation.name || intl.formatMessage({ id: 'untitled_visualisation' })}
        onChangeTitle={onChangeTitle}
        onBeginEditTitle={onBeginEditTitle}
        saveStatusId={saveStatusId}
        actionButtons={actionButtons}
        savingFailed={savingFailed}
        timeToNextSave={timeToNextSave}
      />
    );
  }
}

VisualisationHeader.propTypes = {
  intl: intlShape,
  isUnsavedChanges: PropTypes.bool,
  savingFailed: PropTypes.bool,
  timeToNextSave: PropTypes.number,
  visualisation: PropTypes.shape({
    name: PropTypes.string.isRequired,
  }).isRequired,
  onVisualisationAction: PropTypes.func.isRequired,
  onSaveVisualisation: PropTypes.func.isRequired,
  onChangeTitle: PropTypes.func,
  onBeginEditTitle: PropTypes.func,
};

export default injectIntl(VisualisationHeader);
