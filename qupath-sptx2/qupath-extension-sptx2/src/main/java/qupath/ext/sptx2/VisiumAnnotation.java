/*-
 * #%L
 * ST-AnD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ST-AnD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.sptx2;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.locationtech.jts.geom.Geometry;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;

import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import javafx.geometry.Point2D;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class VisiumAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	// final private static Logger logger = LoggerFactory.getLogger(SpTxVisiumAnnotation.class);
	
	private ParameterList params;

	private final List<String> operationList = new ArrayList<>(List.of("Spots Only", "Connected Clusters", "Surrounding Regions of Spots"));
	
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public VisiumAnnotation() {
		params = new ParameterList()
			// .addTitleParameter(lastResults)
			.addStringParameter("spatialFile", "Spatial File", "", "Spatial File")
			.addStringParameter("clusterFile", "Cluster File", "", "Cluster File")
			.addDoubleParameter("spotDiameter", "Spot Diameter", 55, GeneralTools.micrometerSymbol(), "Spot Diameter")
			.addDoubleParameter("minSpotDist", "Minimal Spot Distance", 100, GeneralTools.micrometerSymbol(), "Minimal Spot Distance")
			.addChoiceParameter("operation", "Operation", operationList.get(0), operationList, "Operation")
			.addBooleanParameter("rotatedImage", "Is the image rotated?", false, "Is the image rotated?")
			.addBooleanParameter("tissueRegionsOnly", "Tissue regions only?", true, "Tissue regions only?")
			.addBooleanParameter("estPxlSize", "Using estimated pixel size? (default: false)", false, "Using estimated pixel size?")			
			.addDoubleParameter("10xMPP", "microns per pixel on original 10x images (0 == ignored)", 0, GeneralTools.micrometerSymbol(), "Sampled microns per pixel on original images (0 == ignored)")	
			.addDoubleParameter("xShift", "X-Shift", 0, GeneralTools.micrometerSymbol(), "X-Shift")
			.addDoubleParameter("yShift", "Y-Shift", 0, GeneralTools.micrometerSymbol(), "X-Shift");
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			
			final List<PathObject> pathObjects = new ArrayList<PathObject>();
			
			
			try {
				final ImageServer<BufferedImage> server = imageData.getServer();
				
		        final FileReader spatialFileReader = new FileReader(params.getStringParameterValue("spatialFile"));
		        final CSVReader spatialReader = new CSVReader(spatialFileReader);
		        final HashMap<String, List<Integer>> spatialHMap = new HashMap<String, List<Integer>>();
		     
		        String[] spatgialNextRecord;
		        List<Point2D> posList = new ArrayList<Point2D>();
		        
		        while ((spatgialNextRecord = spatialReader.readNext()) != null) {
		        	List<Integer> list = new ArrayList<Integer>();
		        	list.add(Integer.parseInt(spatgialNextRecord[1]));
		        	list.add(Integer.parseInt(spatgialNextRecord[2]));
		        	list.add(Integer.parseInt(spatgialNextRecord[3]));
		        	list.add(Integer.parseInt(spatgialNextRecord[4]));
		        	list.add(Integer.parseInt(spatgialNextRecord[5]));
		        	
		        	posList.add(new Point2D(Double.parseDouble(spatgialNextRecord[4]), Double.parseDouble(spatgialNextRecord[5])));
		        	
		        	spatialHMap.put(spatgialNextRecord[0], list);
		        }
		        
		        spatialReader.close();
		        
	        	final double spotDiameter = params.getDoubleParameterValue("spotDiameter");
	        	final double minSpotDist = params.getDoubleParameterValue("minSpotDist");		        
		        
	        	double minDistPxl = -1;
		        
		        for(int i = 0; i < posList.size(); i ++) {
		        	for(int j = 0; j < posList.size(); j ++) {
		        		final double distPxl = posList.get(i).distance(posList.get(j));
		        		minDistPxl = (i != j && (minDistPxl < 0 || distPxl < minDistPxl))? distPxl: minDistPxl;
		        	}
		        }
		        
	        	final double imagePixelSizeMicrons = params.getBooleanParameterValue("estPxlSize")? minSpotDist / minDistPxl: server.getPixelCalibration().getAveragedPixelSizeMicrons();
	        	
	        	ImageServerMetadata metadataNew = new ImageServerMetadata.Builder(server.getMetadata())
	        			.pixelSizeMicrons(imagePixelSizeMicrons, imagePixelSizeMicrons)
	    				.build();
	    		
	        	if (!server.getMetadata().equals(metadataNew)) imageData.updateServerMetadata(metadataNew);
	        	
	        	final FileReader clusterFileReader = new FileReader(params.getStringParameterValue("clusterFile"));
		        final CSVReader clusterReader = new CSVReader(clusterFileReader);
		        final HashMap<String, Integer> analysisHMap = new HashMap<String, Integer>();

		        String[] clusterNextRecord;
		        int clsNum = 0;
		        while ((clusterNextRecord = clusterReader.readNext()) != null) {
		            try {
		                final Integer cls = Integer.parseInt(clusterNextRecord[1]);
		                clsNum = cls > clsNum? cls: clsNum;
		                analysisHMap.put(clusterNextRecord[0], cls);
		            } catch (NumberFormatException nfe) {}
		        }
		        clusterReader.close();

		        final Color[] palette = new Color[clsNum+1];
	    	    for(int i = 0; i < clsNum+1; i++) palette[i] = Color.getHSBColor((float) i / (float) clsNum+1, 0.85f, 1.0f);
		    			        
		        Set<String> barcodeSet = spatialHMap.keySet();
	        	final double sampledPixelSizeMicrons = params.getDoubleParameterValue("10xMPP");	  
		        final double pixelSizeRatio = sampledPixelSizeMicrons > 0.0? sampledPixelSizeMicrons / imagePixelSizeMicrons: 1.0;
		        
		        if(params.getChoiceParameterValue("operation").equals(operationList.get(0))) {
		            final double xShiftMicrons = params.getDoubleParameterValue("xShift");
		            final double yShiftMicrons = params.getDoubleParameterValue("yShift");
					final int rad = (int)Math.round(0.5*spotDiameter/imagePixelSizeMicrons);
					final int dia = (int)Math.round(spotDiameter/imagePixelSizeMicrons);
					final int xshift = (int)Math.round(xShiftMicrons/imagePixelSizeMicrons);
					final int yshift = (int)Math.round(yShiftMicrons/imagePixelSizeMicrons);
					
			        for(String barcode: barcodeSet) {
			        	List<Integer> list = spatialHMap.get(barcode);
			        	
			        	final int in_tissue = list.get(0);
			        	final int pxl_row_in_fullres = (int)Math.round(list.get(3) * pixelSizeRatio);
			        	final int pxl_col_in_fullres = (int)Math.round(list.get(4) * pixelSizeRatio);
			        	
			        	if(params.getBooleanParameterValue("tissueRegionsOnly") && (in_tissue == 1) || !params.getBooleanParameterValue("tissueRegionsOnly")) {
			        		final int cluster = analysisHMap.containsKey(barcode)? analysisHMap.get(barcode): 0;
							final String pathObjName = barcode;
							final String pathClsName = barcode;
							
							ROI pathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad+xshift, pxl_row_in_fullres-rad+yshift, dia, dia, null);
							
					    	final PathClass pathCls = PathClassFactory.getPathClass(pathClsName);
					    	final PathAnnotationObject pathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
					    	
					    	pathObj.setName(pathObjName);
					    	pathObj.setColorRGB(palette[cluster].getRGB());
					    	
							final MeasurementList pathObjMeasList = pathObj.getMeasurementList();
							
							pathObjMeasList.close();
							pathObjects.add(pathObj);  
							
			        	}
			        }
		        }
		        else if(params.getChoiceParameterValue("operation").equals(operationList.get(1))) {
		            final double xShiftMicrons = params.getDoubleParameterValue("xShift");
		            final double yShiftMicrons = params.getDoubleParameterValue("yShift");
		            
			        for(String barcode: barcodeSet) {
			        	List<Integer> list = spatialHMap.get(barcode);
			        	
			        	final int in_tissue = list.get(0);
			        	final int pxl_row_in_fullres = (int)Math.round(list.get(3) * pixelSizeRatio);
			        	final int pxl_col_in_fullres = (int)Math.round(list.get(4) * pixelSizeRatio);
			        	
			        	if(params.getBooleanParameterValue("tissueRegionsOnly") && (in_tissue == 1) || !params.getBooleanParameterValue("tissueRegionsOnly")) {
			        		final int cluster = analysisHMap.containsKey(barcode)? analysisHMap.get(barcode): 0;
			        		
							final String pathObjName = barcode;
							final String pathClsName = barcode;
									
							final int rad = (int)Math.round(0.5*spotDiameter/imagePixelSizeMicrons);
							final int dia = (int)Math.round(spotDiameter/imagePixelSizeMicrons);
							
							final int rad2 = (int)Math.round(Math.sqrt(2.0  * Math.pow(0.5*spotDiameter/imagePixelSizeMicrons, 2.0)));
							final int dia2 = (int)Math.round(2.0 * Math.sqrt(2.0  * Math.pow(0.5*spotDiameter/imagePixelSizeMicrons, 2.0)));
							
							final int xshift = (int)Math.round(xShiftMicrons/imagePixelSizeMicrons);
							final int yshift = (int)Math.round(yShiftMicrons/imagePixelSizeMicrons);
							
							final ROI spotPathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad+xshift, pxl_row_in_fullres-rad+yshift, dia, dia, null);		
							final ROI surroundingPathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad2+xshift, pxl_row_in_fullres-rad2+yshift, dia2, dia2, null);	
							
							final Geometry spotPathGeom = spotPathRoi.getGeometry();
							final Geometry expandedPathGeom = surroundingPathRoi.getGeometry();
							
							final Geometry surroundingPathGeom = expandedPathGeom.difference(spotPathGeom);
							final ROI surroundingPathROI = GeometryTools.geometryToROI(surroundingPathGeom, ImagePlane.getDefaultPlane());
							
					    	final PathClass surroundingPathCls = PathClassFactory.getPathClass(pathClsName);
					    	final PathAnnotationObject surroundingPathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(surroundingPathROI, surroundingPathCls);

					    	surroundingPathObj.setName(pathObjName);
					    	surroundingPathObj.setColorRGB(palette[cluster-1].getRGB());

							pathObjects.add(surroundingPathObj);
			        	}
			        }
		        }
		        else { 
			        final ImageServer<BufferedImage> imageServer = imageData.getServer();
					final int imageWidth = imageServer.getWidth();
					final int imageHeight = imageServer.getHeight();
		            final double xShiftMicrons = params.getDoubleParameterValue("xShift");
		            final double yShiftMicrons = params.getDoubleParameterValue("yShift");
					final int xshift = (int)Math.round(xShiftMicrons/imagePixelSizeMicrons);
					final int yshift = (int)Math.round(yShiftMicrons/imagePixelSizeMicrons);
					
			        for(int c = 1; c < clsNum; c ++) {
			        	final BufferedImage image = new BufferedImage(imageWidth/4, imageHeight/4, BufferedImage.TYPE_BYTE_GRAY);
			        	final Graphics2D graphic = image.createGraphics();
			            
			        	for(String barcode: barcodeSet) {
				        	List<Integer> list = spatialHMap.get(barcode);
				        	
				        	final int in_tissue = list.get(0);
				        	final int pxl_row_in_fullres = (int)Math.round(list.get(3) * pixelSizeRatio);
				        	final int pxl_col_in_fullres = (int)Math.round(list.get(4) * pixelSizeRatio);

				        	if(in_tissue == 1) {
				        		final Integer cluster = analysisHMap.get(barcode);
				        		final int r = (int)Math.round(Math.ceil(0.5*minSpotDist/Math.sqrt(3.0)*2.0/imagePixelSizeMicrons)/4.0);
						    	
				        		if (cluster == c) {
									final Polygon p = new Polygon();
									final int x = (int)Math.round((double)(pxl_col_in_fullres+xshift)/4.0);
									final int y = (int)Math.round((double)(pxl_row_in_fullres+yshift)/4.0);

								    for (int i = 0; i < 6; i++)								    	
									    if(params.getBooleanParameterValue("rotatedImage")) {
									    	
									    	p.addPoint((int) (x + r * Math.cos((i * 2 * Math.PI / 6)+(2 * Math.PI / 12))),
									    			(int) (y + r * Math.sin((i * 2 * Math.PI / 6)+(2 * Math.PI / 12))));
									    }
									    else {
									    	p.addPoint((int) (x + r * Math.cos((i * 2 * Math.PI / 6))),
									    			(int) (y + r * Math.sin((i * 2 * Math.PI / 6))));
									    }
								    graphic.setColor(Color.WHITE);
								    graphic.fillPolygon(p);		        			
				        		}
				        	}
			        	}
			        	
			        	final ByteProcessor bp = new ByteProcessor(image);
			        	bp.setThreshold(127.5, 255, ImageProcessor.NO_LUT_UPDATE);
			        	final Roi roiIJ = new ThresholdToSelection().convert(bp);
			        	
			        	 if (roiIJ != null) {
					    	final ROI roi = IJTools.convertToROI(roiIJ, 0, 0, 4, ImagePlane.getDefaultPlane());
					    	final PathClass pathCls = PathClassFactory.getPathClass("cluster-"+String.valueOf(c));
					    	final PathObject p = PathObjects.createAnnotationObject(roi, pathCls);
					    	pathObjects.add(p);
					    }
			        }
		        }
			}
			catch(Exception e) {			
			}
			
			if (Thread.currentThread().isInterrupted())
				return null;
			
			if (pathObjects == null || pathObjects.isEmpty())
				lastResults =  "No regions detected!";
			else if (pathObjects.size() == 1)
				lastResults =  "1 region detected";
			else
				lastResults =  pathObjects.size() + " regions detected";
			
			return pathObjects;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}
	
	
	
	
//	private static List<PathObject> convertToPathObjects(ByteProcessor bp, double minArea, boolean smoothCoordinates, Calibration cal, double downsample, double maxHoleArea, boolean excludeOnBoundary, boolean singleAnnotation, ImagePlane plane, List<PathObject> pathObjects) {
////		
////		var roiIJ = new ThresholdToSelection().convert(bp);
////		var roi = IJTools.convertToROI(roiIJ, cal, downsample, plane);
////		roi = RoiTools.removeSmallPieces(roi, minArea, maxHoleArea);
////		List<PathObject> annotations = new ArrayList<>();
////		if (singleAnnotation)
////			annotations.add(PathObjects.createAnnotationObject(roi));
////		else {
////			for (var roi2 : RoiTools.splitROI(roi)) {
////				annotations.add(PathObjects.createAnnotationObject(roi2));				
////			}
////		}
////		for (var annotation : annotations)
////			annotation.setLocked(true);
////		return annotations;
////			
//		
//		List<PolygonRoi> rois = RoiLabeling.getFilledPolygonROIs(bp, Wand.FOUR_CONNECTED);
//		if (pathObjects == null)
//			pathObjects = new ArrayList<>(rois.size());
//		
//		// We might need a copy of the original image
//		boolean fillAllHoles = maxHoleArea <= 0;
//		ByteProcessor bpOrig = !fillAllHoles ? (ByteProcessor)bp.duplicate() : null;
//		
//		bp.setValue(255);
//		for (PolygonRoi r : rois) {
//			// Check for boundary overlap
//			if (excludeOnBoundary) {
//				Rectangle bounds = r.getBounds();
//				if (bounds.x <= 0 || bounds.y <= 0 ||
//						bounds.x + bounds.width >= bp.getWidth()-1 || 
//						bounds.y + bounds.height >= bp.getHeight()-1)
//					continue;
//			}
//			bp.setRoi(r);
//			if (bp.getStatistics().area < minArea)
//				continue;
//						
//			bp.fill(r); // Fill holes as we go - it might matter later
////			if (smoothCoordinates) {
//////				r = new PolygonRoi(r.getInterpolatedPolygon(2.5, false), Roi.POLYGON);
////				r = new PolygonRoi(r.getInterpolatedPolygon(Math.min(2.5, r.getNCoordinates()*0.1), false), Roi.POLYGON); // TODO: Check this smoothing - it can be troublesome, causing nuclei to be outside cells
////			}
//			
//			PolygonROI pathPolygon = IJTools.convertToPolygonROI(r, cal, downsample, plane);
////			if (pathPolygon.getArea() < minArea)
////				continue;
//			// Smooth the coordinates, if we downsampled quite a lot
//			if (smoothCoordinates) {
//				pathPolygon = ROIs.createPolygonROI(ShapeSimplifier.smoothPoints(pathPolygon.getAllPoints()), ImagePlane.getPlaneWithChannel(pathPolygon));
//				pathPolygon = ShapeSimplifier.simplifyPolygon(pathPolygon, downsample/2);
//			}
//			pathObjects.add(PathObjects.createAnnotationObject(pathPolygon));
//		}
//		
//		
//		if (Thread.currentThread().isInterrupted())
//			return null;
//		
//		// TODO: Optimise this - the many 'containsObject' calls are a (potentially easy-to-fix) bottleneck
//		if (!fillAllHoles) {
//			// Get the holes alone
//			bp.copyBits(bpOrig, 0, 0, Blitter.DIFFERENCE);
////			new ImagePlus("Binary", bp).show();
//			bp.setThreshold(127, Double.POSITIVE_INFINITY, ImageProcessor.NO_LUT_UPDATE);
//			
//			List<PathObject> holes = convertToPathObjects(bp, maxHoleArea, smoothCoordinates, cal, downsample, 0, false, false, plane, null);
//			
//			// For each object, fill in any associated holes
//			List<Area> areaList = new ArrayList<>();
//			for (int ind = 0; ind < pathObjects.size(); ind++) {
//				if (holes.isEmpty())
//					break;
//				
//				PathObject pathObject = pathObjects.get(ind);
//				var geom = PreparedGeometryFactory.prepare(pathObject.getROI().getGeometry());
//				areaList.clear();
//				Iterator<PathObject> iter = holes.iterator();
//				while (iter.hasNext()) {
//					PathObject hole = iter.next();
//					if (geom.covers(hole.getROI().getGeometry())) {
//						areaList.add(RoiTools.getArea(hole.getROI()));
//						iter.remove();
//					}
//				}
//				if (areaList.isEmpty())
//					continue;
//				
//				// If we have some areas, combine them
//				// TODO: FIX MAJOR BOTTLENECK HERE!!!
//				Area hole = areaList.get(0);
//				for (int i = 1; i < areaList.size(); i++) {
//					hole.add(areaList.get(i));
//					if (i % 100 == 0) {
//						logger.debug("Added hole " + i + "/" + areaList.size());
//						if (Thread.currentThread().isInterrupted())
//							return null;
//					}
//				}
//				
//				// Now subtract & create a new object
//				ROI pathROI = pathObject.getROI();
//				if (RoiTools.isShapeROI(pathROI)) {
//					Area areaMain = RoiTools.getArea(pathROI);
//					areaMain.subtract(hole);
//					pathROI = RoiTools.getShapeROI(areaMain, pathROI.getImagePlane());
//					pathObjects.set(ind, PathObjects.createAnnotationObject(pathROI));
//				}
//			}
//		}
//		
//		
//		// This is a clumsy way to do it...
//		if (singleAnnotation) {
//			ROI roi = null;
//			for (PathObject annotation : pathObjects) {
//				ROI currentShape = annotation.getROI();
//				if (roi == null)
//					roi = currentShape;
//				else
//					roi = RoiTools.combineROIs(roi, currentShape, CombineOp.ADD);
//			}
//			pathObjects.clear();
//			if (roi != null)
//				pathObjects.add(PathObjects.createAnnotationObject(roi));
//		}
//		
//		
//		
//		// Lock the objects
//		for (PathObject pathObject : pathObjects)
//			((PathAnnotationObject)pathObject).setLocked(true);
//		
//		return pathObjects;
//	}
	

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		// boolean micronsKnown = imageData.getServer().getPixelCalibration().hasPixelSizeMicrons();
		// params.setHiddenParameters(!micronsKnown, "requestedPixelSizeMicrons", "minAreaMicrons", "maxHoleAreaMicrons");
		// params.setHiddenParameters(micronsKnown, "requestedDownsample", "minAreaPixels", "maxHoleAreaPixels");
		return params;
	}

	@Override
	public String getName() {
		return "Simple tissue detection";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}


	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
	}


	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new AnnotationLoader(), getParameterList(imageData), imageData, parentObject));
	}


	@Override
	protected Collection<? extends PathObject> getParentObjects(final PluginRunner<BufferedImage> runner) {
		
		PathObjectHierarchy hierarchy = getHierarchy(runner);
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
//		PathObjectHierarchy hierarchy = runner.getImageData().getHierarchy();
//		PathObject pathObjectSelected = runner.getSelectedObject();
//		if (pathObjectSelected instanceof PathAnnotationObject || pathObjectSelected instanceof TMACoreObject)
//			return Collections.singleton(pathObjectSelected);
//		return Collections.singleton(hierarchy.getRootObject());
	}


	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
//		list.add(PathAnnotationObject.class);
		list.add(PathRootObject.class);
		return list;
	}

}
