import React, { PropTypes } from 'react';
import { Link } from 'react-router';

require('./NavWorkspaceSwitch.scss');

export default function NavWorkspaceSwitch({ profile }) {
  return (
    <div className="NavWorkspaceSwitch">
      {profile.admin && <Link to="/admin/users">Admin view</Link>}
    </div>
  );
}

NavWorkspaceSwitch.propTypes = {
  profile: PropTypes.shape({
    admin: PropTypes.bool.isRequired,
  }).isRequired,
};
