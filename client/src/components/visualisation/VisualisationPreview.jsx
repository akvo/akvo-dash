import React, { PropTypes } from 'react';
import DashBarChart from '../charts/DashBarChart';
import DashLineChart from '../charts/DashLineChart';
import DashAreaChart from '../charts/DashAreaChart';
import DashPieChart from '../charts/DashPieChart';
import DashScatterChart from '../charts/DashScatterChart';
import DashMap from '../charts/DashMap';

require('../../styles/VisualisationPreview.scss');

const getChartPreview = (visualisation, datasets) => {
  let output;
  let datasetColumn;

  switch (visualisation.visualisationType) {
    case 'bar':
      datasetColumn = visualisation.datasetColumnX;

      if (datasetColumn !== null) {
        output = <DashBarChart visualisation={visualisation} datasets={datasets} />;
      } else {
        output = <div>Pie chart image placeholder</div>;
      }

      return output;

    case 'line':
      datasetColumn = visualisation.datasetColumnX;

      if (datasetColumn !== null) {
        output = <DashLineChart visualisation={visualisation} datasets={datasets} />;
      } else {
        output = <div>Line chart image placeholder</div>;
      }

      return output;

    case 'area':
      datasetColumn = visualisation.datasetColumnX;

      if (datasetColumn !== null) {
        output = <DashAreaChart visualisation={visualisation} datasets={datasets} />;
      } else {
        output = <div>Area chart image placeholder</div>;
      }

      return output;

    case 'donut':
    case 'pie':
      datasetColumn = visualisation.datasetColumnX;

      if (datasetColumn !== null) {
        output = <DashPieChart visualisation={visualisation} datasets={datasets} />;
      } else {
        output = <div>Pie chart image placeholder</div>;
      }

      return output;

    case 'scatter':
      if (visualisation.datasetColumnX !== null && visualisation.datasetColumnY !== null) {
        output = <DashScatterChart visualisation={visualisation} datasets={datasets} />;
      } else {
        output = <div>Scatter chart image placeholder</div>;
      }

      return output;

    case 'map':
      output = <DashMap visualisation={visualisation} datasets={datasets} />;

      if (visualisation.datasetColumnX !== null && visualisation.datasetColumnY !== null) {
        output = <DashMap visualisation={visualisation} datasets={datasets} />;
      } else {
        output = <div>Map visualisation image placeholder</div>;
      }

      return output;

    default:
      return null;
  }
};

export default function CreateVisualisationPreview({ visualisation, datasets }) {
  const chart = getChartPreview(visualisation, datasets);
  return (
    <div className="VisualisationPreview">
      {chart}
    </div>
  );
}

CreateVisualisationPreview.propTypes = {
  visualisation: PropTypes.object.isRequired,
  datasets: PropTypes.object.isRequired,
};
