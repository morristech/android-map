package sk.gista.android.maps;

import android.graphics.PointF;

public interface MapView extends AndroidComponent {

	void setZoom(int zoom);
	int getZoom();
	
	void setHeading(int heading);
	void setLayer(TmsLayer layer);
	TmsLayer getLayer();
	
	PointF getCenter();
	void setCenter(float x, float y);
	
	PointF mapToScreenAligned(float x, float y);
	void addOverlay(Overlay overlay);
	
	void moveToLocation(float x, float y);
	
	/**
	 * Zoom with animation
	 * @param zoom
	 */
	void zoomTo(int zoom);
	
	void setOnZoomChangeListener(MapListener listener);
	void redraw();
	
	void recycle();
	
	PointF mapToScreen(float x, float y);
	PointF screenToMap(float x, float y);
	float getResolution();
	
	
	public interface MapListener {
		void onLayerChanged(TmsLayer layer);
		void onZoomChanged(int zoom);
	}
}
