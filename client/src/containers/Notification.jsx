/* eslint-disable no-unused-expressions */
/* eslint-disable no-underscore-dangle */
import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { connect } from 'react-redux';
import { hideNotification } from '../actions/notification';

require('./Notification.scss');

// When autohide is used, how long to wait in ms before hiding the notification with no user input
const autohideDelay = 3000;

class Notification extends Component {
  constructor(props) {
    super(props);
    this._isMounted = false;
    this.state = {
      onscreen: false,
    };
  }

  componentDidMount() {
    this._isMounted = true;
    if (this.props.autohide) {
      this._isMounted && this.scheduleAutoHide();
    }
    // Force one additional render so we can change the className and animate position with css.
    //  Lint exception is OK here - it's designed to prevent re-renders, but we want the re-render.
    //  eslint-disable-next-line react/no-did-mount-set-state
    setTimeout(() => this._isMounted && this.setState({ onscreen: true }), 0);
  }

  // eslint-disable-next-line no-unused-vars
  componentDidUpdate(prevProps, prevState) {
    if (this.props.autohide) {
      this.scheduleAutoHide();
    } else {
      clearTimeout(this.timeout);
    }
  }

  componentWillUnmount() {
    clearTimeout(this.timeout);
    this._isMounted = false;
  }

  scheduleAutoHide() {
    const { dispatch } = this.props;
    const unmount = () => dispatch(hideNotification());
    const hide = () => {
      this._isMounted && this.setState({ onscreen: false });
      this.timeout = setTimeout(unmount, 500);
    };

    this.timeout = setTimeout(hide, autohideDelay);
  }

  render() {
    const { level, message, dispatch } = this.props;
    return (
      <div
        className={`Notification ${level} ${this.state.onscreen ? 'onscreen' : 'offscreen'}`}
      >
        <span
          className="message"
        >
          {message}
        </span>
        <span
          className="close clickable"
          onClick={() => dispatch(hideNotification())}
        >
          ✕
        </span>
      </div>
    );
  }
}

Notification.propTypes = {
  level: PropTypes.oneOf(['info', 'warn', 'error', 'success']).isRequired,
  message: PropTypes.string.isRequired,
  autohide: PropTypes.bool,
  dispatch: PropTypes.func.isRequired,
};

export default connect()(Notification);
