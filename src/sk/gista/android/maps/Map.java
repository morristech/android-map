package sk.gista.android.maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import static java.lang.String.format;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import sk.gista.android.maps.Layer.Tile;
import sk.gista.android.maps.Layer.TileListener;
import sk.gista.android.maps.MapEventsGenerator.MapControlListener;
import sk.gista.android.utils.CustomAnimation;
import sk.gista.android.utils.TmsVisualDebugger;
import sk.gista.android.utils.Utils;
import sk.gista.android.utils.CustomAnimation.CompositeAnimation;

public class Map extends View implements TileListener, MapView, MapControlListener {

	private static String TAG = Map.class.getSimpleName();
	
	private PointF center;
	private BBox bbox;
	private int zoomLevel = 1;
	private int heading;
	
	private float tileWidth;
	private float tileHeight;

	// size of screen (map) in pixels
	private int width;
	private int height;

	// coordinates on map and screen to compute aligned positions from map to the screen
	private PointF firstTilePosition = new PointF();
	private PointF firstTilePositionPx = new PointF();
	
	private Point firstVisibleTile = new Point();
	private Point lastVisibleTile = new Point();
	
	// zoom
	private float zoomPinch = 1f;
	private Bitmap zoomBackground;
	private boolean showZoomBackground;
	
	//private Matrix overlayMatrix;
	
	private TmsLayer tmsLayer;
	private TilesManager tilesManager;
	private List<Overlay> overlays;
	
	private boolean drawOverlays = true;
	private boolean drawGraphicalScale = true;
	
	// drawing styles
	private Paint imagesStyle;
	private Paint mapStyle;
	private Paint screenBorderStyle;
	private Paint screenBorderStyle2;
	private Paint whiteStyle;
	private Paint scaleStyle;
	
	private float scaleWidth = 141.73236f;
	private String scaleText;
	
	private Timer animTimer = new Timer();
	private TmsVisualDebugger visualDebugger;
	private boolean isPeriodicallyRedrawing;
	
	private MapListener mapListener;
	private MapEventsGenerator mapEventsGenerator;
	
	private PointF alignedCenter;
	private PointF bgLeftBottom = new PointF();
	private PointF bgRightTop = new PointF();
	
	private ZoomAnimation zoomAnimation;
	
	private int size;
	
	public Map(Context context) {
		super(context);
		postInit();
	}
	
	public Map(Context context, AttributeSet attrs) {
		super(context, attrs);
		postInit();
	}
	
	private void postInit() {
		imagesStyle = new Paint();
		imagesStyle.setFilterBitmap(true);
		
		mapStyle = new Paint();
		mapStyle.setStrokeWidth(2f);
		mapStyle.setStyle(Paint.Style.STROKE);
		mapStyle.setColor(Color.argb(255, 0, 0, 0));

		screenBorderStyle = new Paint();
		screenBorderStyle.setStyle(Paint.Style.STROKE);
		screenBorderStyle.setStrokeWidth(2f);
		screenBorderStyle.setAntiAlias(true);
		screenBorderStyle.setColor(Color.argb(255, 20, 40, 120));

		screenBorderStyle2 = new Paint(screenBorderStyle);
		screenBorderStyle2.setColor(Color.rgb(150, 30, 50));
		
		whiteStyle = new Paint();
		whiteStyle.setColor(Color.WHITE);
		
		scaleStyle = new Paint();
		scaleStyle.setFakeBoldText(true);
		scaleStyle.setAntiAlias(true);
		
		overlays = new ArrayList<Overlay>(1);
		mapEventsGenerator = new MapEventsGenerator(this);
		mapEventsGenerator.setMapControlListener(this);
	}
	
	public void setLayer(TmsLayer layer) {
		if (tmsLayer != layer) {
			
			if (tilesManager != null) {
				tilesManager.cancelAll();
				tilesManager.clearCache();
			}
			tmsLayer = layer;
			if (layer != null) {
				tilesManager = new TilesManager(this);
				tilesManager.addTileListener(this);
				bbox = layer.getBoundingBox();
				center = new PointF((bbox.minX + bbox.maxX) / 2f, (bbox.minY + bbox.maxY) / 2f);
		
				visualDebugger = new TmsVisualDebugger(this);
				//setZoom(1);
				onZoomChange(zoomLevel, zoomLevel);
			}
			if (mapListener != null) {
				mapListener.onLayerChanged(layer);
			}
		}
	}
	
	public int getZoom() {
		if (zoomAnimation != null) {
			return zoomAnimation.zoom;
		}
		return zoomLevel;
	}
	
	public void zoomTo(final int zoom) {
		if (this.zoomLevel == zoom) {
			return;
		}
		if (zoom >= 0 && zoom < tmsLayer.getResolutions().length) {
			if (zoomAnimation != null) {
				zoomAnimation.stop();
			}
			zoomAnimation = new ZoomAnimation(zoom);
			zoomAnimation.setDuration(400);
			zoomAnimation.setFramesCount(7);
			zoomAnimation.setView(this);
			zoomAnimation.start();
		}
	}
	
	public void setZoom(int zoom) {
		if (this.zoomLevel == zoom) {
			return;
		}
		if (zoom >= 0 && zoom < tmsLayer.getResolutions().length) {
			int oldZoom = this.zoomLevel;
			this.zoomLevel = zoom;
			onZoomChange(oldZoom, zoom);
			if (mapListener != null) {
				mapListener.onZoomChanged(zoom);
			}
		}
	}

	public PointF getCenter() {
		return center;
	}
	
	public void setCenter(float x, float y) {
		if (center != null) {
			center.x = x;
			center.y = y;
		} else {
			center = new PointF(x, y);
		}
		alignedCenter = null;
	}
	
	public void recycle() {
		if (tilesManager != null) {
			tilesManager.cancelAll();
			tilesManager.clearCache();
		}
	}
	
	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		Log.i(TAG, format("width: %d height: %d", w, h));
		//Log.i(TAG, "Cached tiles: "+tilesCache.size());
		if (zoomBackground != null) {
			zoomBackground.recycle();
			zoomBackground = null;
		}
		size = (int) Math.ceil(Math.sqrt(w*w+h*h));
		width = w;
		height = h;
		//clearTiles();
	}

	protected void onZoomChange(int oldZoom, int zoom) {
		tileWidth = tmsLayer.getTileWidth() * getResolution();
		tileHeight = tmsLayer.getTileHeight() * getResolution();
		tilesManager.cancelAll();
		tilesManager.clearCache();
		
		//double factor = 39.3701; // meters
		//double scale = getResolution()* 72.0 * factor;
		//scale = scale/20.0;
		double scale = scaleWidth*getResolution();
		String scaleUnitText;
		if (scale >= 1000) {
			scale /= 1000.0;
			scaleUnitText = "km";
		} else {
			scaleUnitText = "m";
		}
		scaleText = format("%.2f %s", scale, scaleUnitText);
	}

	public TmsLayer getLayer() {
		return tmsLayer;
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		return mapEventsGenerator.onTouchEvent(event);
	}

	PointF centerAtZoomStart = new PointF();
	@Override
	protected void onDraw(Canvas canvas) {
		canvas.drawRGB(255, 255, 255);
		if (tmsLayer == null) {
			return;
		}
		
		validateMap();
		
		canvas.scale(1, -1);
		canvas.translate(0, -height);// or -(height-1) ?
		float scale = 0.5f;
		
		PointF ca = mapToScreenAligned(center.x, center.y);
		float compX = alignedCenter.x-ca.x;
		float compY = alignedCenter.y-ca.y;
		//Log.i(TAG, "Zoom: "+zoomLevel+" scale: "+zoomPinch+" Compensation: "+compX+", "+compY);
		
		canvas.save();
		canvas.translate(compX, compY);
		if (showZoomBackground && zoomBackground != null) {
			//Log.i(TAG, "drawing background   zoomPinch: "+zoomPinch);
			PointF bgAlignedPos = mapToScreenAligned(bgLeftBottom.x, bgLeftBottom.y);
			PointF rightTop = mapToScreenAligned(bgRightTop.x, bgRightTop.y);
			float bgScale = (rightTop.x-bgAlignedPos.x)/(float) width;
			
			canvas.save();
			//Log.i(TAG, "BG img aligned pos: "+bgAlignedPos.x+", "+bgAlignedPos.y);
			//Paint p = new Paint();
			//p.setColor(Color.GREEN);
			//canvas.drawRect(bgAlignedPos.x, bgAlignedPos.y, rightTop.x, rightTop.y, p);
			canvas.scale(bgScale, -bgScale, bgAlignedPos.x, bgAlignedPos.y);
			canvas.drawBitmap(zoomBackground, bgAlignedPos.x, bgAlignedPos.y-height, imagesStyle);
			canvas.restore();
		}
		
		canvas.save();
		canvas.scale(zoomPinch, zoomPinch, width/2f, height/2f);
		//canvas.rotate(-heading, width/2f, height/2f);
		
		long t1 = System.currentTimeMillis();
		//Log.i(TAG, format("bbox=%f, %f, %f, %f center=%f, %f", bbox.minX, bbox.minY, bbox.maxX, bbox.maxY, center.x, center.y));
		//Log.i(TAG, format("firstTileX=%d firstTileY=%d", firstTileX, firstTileY));
		// TODO: check that firstTileX/Y and lastTileX/Y aren't too high (when onZoomChange() or something like that
		// wasn't called)
		int notAvailableTiles = 0;
		PointF o = screenToMap(0, 0);
		if (o.x <= bbox.maxX && o.y <= bbox.maxY) {
			for (int x = firstVisibleTile.x; x <= lastVisibleTile.x; x++) {
				for (int y = firstVisibleTile.y; y <= lastVisibleTile.y; y++) {
					Tile tile = null;
					if (zoomPinch == 1f) {
						tile = tilesManager.getTile(x, y);
					} else if (tilesManager.hasInCache(x, y)) {
						tile = tilesManager.getTile(x, y);
					}
					if (tile != null && tile.getImage() != null) {
						float left = firstTilePositionPx.x+(256*(x-firstVisibleTile.x));
						float bottom = firstTilePositionPx.y+(y-firstVisibleTile.y)*256;
						if (showZoomBackground) {
							canvas.drawRect(left, bottom, left+256, bottom+256, whiteStyle);
						}
						canvas.scale(1, -1, left, bottom+128);
						canvas.drawBitmap(tile.getImage(), left, bottom, imagesStyle);
						//visualDebugger.drawTile(canvas, x, y);
						canvas.scale(1, -1, left, bottom+128);
					//} else if (zoomPinch == 1f) {
					} else {
						notAvailableTiles++;
					}
				}
			}
		}
		if (notAvailableTiles == 0 && showZoomBackground) {
			//Log.i(TAG, "Have all tiles");
			showZoomBackground = false;
			// TODO recycle bg or something better 
			zoomBackground = null;
		}
		
		canvas.restore();
		if (drawOverlays) {
			PointF startP = mapToScreenAligned(bbox.minX, bbox.minY);
			PointF endP = mapToScreenAligned(bbox.maxX, bbox.maxY);
			float[] border = {startP.x, startP.y, endP.x, endP.y};
			float maxValue = 5000;
			for (int i = 0; i < 4; i++) {
				if (border[i] > maxValue) {
					border[i] = maxValue;
				} else if (border[i] < -maxValue) {
					border[i] = -maxValue;
				}
			}
			canvas.drawRect(border[0], border[1], border[2], border[3], mapStyle);
		}

		if (drawOverlays) {
			for (Overlay overlay : overlays) {
				overlay.onDraw(this, canvas, zoomPinch);
			}
		}
		/*
		// screen border
		canvas.drawRect(0, 0, width, height, screenBorderStyle2);
		canvas.drawArc(new RectF(-3, -3, 3, 3), 0, 360, true, screenBorderStyle2);
		
		canvas.rotate(heading, width/2f, height/2f);
		canvas.drawRect(0, 0, width, height, screenBorderStyle);
		*/
		canvas.restore();
		if (drawGraphicalScale) {
			drawGraphicalScale(canvas);
		}
	}

	private void drawGraphicalScale(Canvas canvas) {
		canvas.save();
		canvas.translate(0, height-24);
		scaleStyle.setColor(Color.GRAY);
		scaleStyle.setAlpha(150);
		canvas.drawRect(5, 4, 10+scaleWidth, 20, scaleStyle);
		scaleStyle.setAlpha(255);
		scaleStyle.setColor(Color.BLACK);
		canvas.drawRect(5, 4, 10+scaleWidth, 7, scaleStyle);

		canvas.scale(1, -1, 0, 9);
		canvas.drawText(scaleText, 10, 9, scaleStyle);
		canvas.restore();
	}
	
	private void validateMap() {
		Point s = getTileAtScreen(0, 0);
		Point e = getTileAtScreen(width, height);
		
		firstVisibleTile.x = s.x > 0 ? s.x : 0;
		firstVisibleTile.y = s.y > 0 ? s.y : 0;
		
		//Log.i(TAG, format("left-top tile: [%d, %d] right-bottom tile: [%d, %d]", s.x, s.y, e.x, e.y));
		lastVisibleTile.x = (int) ((bbox.maxX - bbox.minX) / tileWidth);
		lastVisibleTile.y = (int) ((bbox.maxY - bbox.minY) / tileHeight);

		lastVisibleTile.x = e.x < lastVisibleTile.x ? e.x : lastVisibleTile.x;
		lastVisibleTile.y = e.y < lastVisibleTile.y ? e.y : lastVisibleTile.y;
		
		firstTilePosition.x = bbox.minX + tileWidth * firstVisibleTile.x;
		firstTilePosition.y = bbox.minY + tileHeight * firstVisibleTile.y;
		PointF p = mapToScreen(firstTilePosition.x, firstTilePosition.y);
		firstTilePositionPx.x = Math.round(p.x);
		firstTilePositionPx.y = Math.round(p.y);
		if (alignedCenter == null) {
			alignedCenter = mapToScreenAligned(center.x, center.y);
		}
	}
	
	private Point getTileAtScreen(int x, int y) {
		assert x <= width && y <= height : "point outside the screen";
		PointF mapPos = screenToMap(x, y);
		//float tileX = (mapPos.x - bbox.minX) / tileWidth;
		//float tileY = (mapPos.y - bbox.minY) / tileHeight;
		//return new Point((int) Math.floor(tileX), (int) Math.floor(tileY));
		return tmsLayer.getTileAt(mapPos, zoomLevel);
	}

	public final PointF screenToMap(float x, float y) {
		Matrix m = new Matrix();
		//m.postRotate(heading, width/2f, height/2f);
		m.postScale(1f/zoomPinch, 1f/zoomPinch, width/2f, height/2f);
		float[] pos = new float[] {x, y};
		m.mapPoints(pos);
		float offsetX = pos[0] - width / 2f;
		float offsetY = pos[1] - height / 2f;
		
		//float offsetX = x - width / 2f;
		//float offsetY = y - height / 2f;
		return new PointF(center.x + offsetX * getResolution(), center.y + offsetY
				* getResolution());
	}

	public final PointF mapToScreen(float x, float y) {
		float offsetX = x - center.x;
		float offsetY = y - center.y;
		return new PointF(width / 2f + offsetX / getResolution(), height / 2f + offsetY
				/ getResolution());
	}

	public final PointF mapToScreenAligned(float x, float y) {
		float positionOffsetX = x - firstTilePosition.x;
		float positionOffsetY = y - firstTilePosition.y;
		float tx = firstTilePositionPx.x+positionOffsetX/getResolution();
		float ty = firstTilePositionPx.y+positionOffsetY/getResolution();
		Matrix m = new Matrix();
		m.postScale(zoomPinch, zoomPinch, width/2f, height/2f);
		float[] pos = new float[] {tx, ty};
		m.mapPoints(pos);
		return new PointF(pos[0], pos[1]);
	}
	
	public final float getResolution() {
		return (float) tmsLayer.getResolutions()[zoomLevel];
	}

	@Override
	public void onTileLoad(Tile tile) {
		//Log.i(TAG, "onTileLoad: "+tile);
		cerateNewBg = true;
		if (! isPeriodicallyRedrawing) {
			post(new Runnable() {
				
				@Override
				public void run() {
					invalidate();
				}
			});
		}
	}

	@Override
	public void onTileLoadingFailed(Tile tile) {
		Log.w(TAG, "onTileLoadingFailed: "+tile);
	}
	
	private int getClosestZoomLevel(double newZoom) {
		double newResolution = tmsLayer.getResolutions()[zoomLevel]/newZoom;
		double closestResolutionDistance = tmsLayer.getResolutions()[0];
		int indexOfClosestResolution = 0;
		
		for (int i = 0 ; i < tmsLayer.getResolutions().length; i++) {
			double resolution = tmsLayer.getResolutions()[i];
			double distance = Math.abs(resolution-newResolution);
			if (distance < closestResolutionDistance) {
				closestResolutionDistance = distance;
				indexOfClosestResolution = i;
			}
		}
		return indexOfClosestResolution;
	}
	
	private void onZoomPinchEnd() {
		int closestZoomLevel = getClosestZoomLevel(zoomPinch);
		ZoomAnimation animation = new ZoomAnimation(closestZoomLevel);
		animation.setDuration(300);
		animation.setFramesCount(5);
		animation.setView(this);
		animation.start();
	}
	
	public void addOverlay(Overlay overlay) {
		overlays.add(overlay);
	}
	
	@Override
	public void setHeading(int heading) {
		this.heading = heading;
		invalidate();
	}

	@Override
	public void onPause() {
		for (Overlay overlay : overlays) {
			overlay.onPause();
		}
	}

	@Override
	public void onResume() {
		for (Overlay overlay : overlays) {
			overlay.onResume();
		}
	}

	@Override
	public void moveToLocation(final float x, final float y) {
		int screenDistance = (int) (Utils.distance(x, y, center.x, center.y)/getResolution());
		//Log.i(TAG, "distance: "+screenDistance+" px");
		int maxAnimDistance = 2 * (int) Math.sqrt(width*width+height*height);
		
		if (screenDistance < maxAnimDistance) {
			float fraction = (screenDistance/(float) maxAnimDistance);
			MoveAnimation animation = new MoveAnimation(x, y);
			animation.setDuration(100+(int) (500*fraction));
			animation.setFramesCount(2+(int)(10*fraction));
			animation.setView(this);
			animation.start();
		} else {
			setCenter(x, y);
			invalidate();
		}
	}
	
	private void moveAndZoom(final float x, final float y, final int zoom) {
		MoveAnimation moveAnim = new MoveAnimation(x, y);
		ZoomAnimation zoomAnim = new ZoomAnimation(zoom);
		CompositeAnimation animation = new CompositeAnimation(350, 6);
		animation.addAnimation(moveAnim);
		animation.addAnimation(zoomAnim);
		
		animation.setView(this);
		animation.start();
	}

	@Override
	public void redraw() {
		invalidate();
	}

	@Override
	public void setOnZoomChangeListener(MapListener listener) {
		this.mapListener = listener;
	}
	

	@Override
	public void onTapStart(float x, float y) {
		Log.i(TAG, "Clicked at: "+x+", "+y);
		startPeriodicalRedrawing();
	}

	public void onTapEnd() {
		stopPeriodicalRedrawing();
		invalidate();
	}
	
	@Override
	public void onMove(float x, float y) {
		setCenter(x, y);
	}

	@Override
	public void onZoom(float zoom) {
		zoomPinch = zoom;
	}
	
	@Override
	public void onZoomEnd() {
		onZoomPinchEnd();
	}

	@Override
	public void onDoubleTap(float x, float y) {
		PointF pos = screenToMap(x, y);
		int newZoom = zoomLevel + 1 < tmsLayer.getResolutions().length? zoomLevel + 1 : zoomLevel;
		moveAndZoom(pos.x , pos.y, newZoom);
	}
	
	private final void startPeriodicalRedrawing() {
		if (isPeriodicallyRedrawing) {
			throw new IllegalStateException();
		}
		isPeriodicallyRedrawing = true;
		animTimer.schedule(new TimerTask() {
			
			@Override
			public void run() {
				post(new Runnable() {
					
					@Override
					public void run() {
						invalidate();
					}
				});
			}
		}, 40, 80);
	}
	
	private final void stopPeriodicalRedrawing() {
		isPeriodicallyRedrawing = false;
		animTimer.cancel();
		animTimer = new Timer();
	}
	
	
	class MoveAnimation extends CustomAnimation {

		private float startX;
		private float startY;
		private float x;
		private float y;
		
		public MoveAnimation(float x, float y) {
			this.x = x;
			this.y = y;
			startX = center.x;
			startY = center.y;
		}
		
		@Override
		public void onFrame(float fraction) {
			setCenter(startX + (x-startX)*fraction, startY + (y-startY)*fraction);
		}

		@Override
		public void onEnd() {}
	}
	
	private boolean cerateNewBg = true;
	
	class ZoomAnimation extends CustomAnimation {

		private int zoom;
		private float endZoomPinch;
		private float startZoomPinch;
		
		public ZoomAnimation(int zoom) {
			this.zoom = zoom;
			endZoomPinch = getResolution() / (float) getLayer().getResolutions()[zoom];
			startZoomPinch = zoomPinch;
		}
		
		@Override
		public void onFrame(final float fraction) {
			zoomPinch = startZoomPinch + (endZoomPinch-startZoomPinch)*fraction;
		}
		
		@Override
		public void onEnd() {
			Log.i(TAG, "****  ZOOM END  ****");
			if (cerateNewBg) {
				Log.i(TAG, "createBackgroundImage "+width+" x "+height);
				zoomBackground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
				Canvas canvas = new Canvas(zoomBackground);
				
				showZoomBackground = false;
				drawOverlays = false;
				drawGraphicalScale = false;
				onDraw(canvas);
				drawOverlays = true;
				drawGraphicalScale = true;
				
				zoomPinch = 1f;
				setZoom(zoom);
				validateMap();
				bgLeftBottom = screenToMap(0, 0);
				bgRightTop = screenToMap(width, height);
				
				cerateNewBg = false;
			} else {
				zoomPinch = 1f;
				setZoom(zoom);
			}
			showZoomBackground = true;
			zoomAnimation = null;
		}
	}
}