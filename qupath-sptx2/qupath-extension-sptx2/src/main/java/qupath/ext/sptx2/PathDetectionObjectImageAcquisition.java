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

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;
import org.imgscalr.Scalr;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Sampling images of detected objects and store into the specific folder.
 * 
 * @author Chao Hui Huang
 *
 */
public class PathDetectionObjectImageAcquisition extends AbstractDetectionPlugin<BufferedImage> {
	
	private ParameterList params;
	
	private String lastResults = null;
	
	final private StringProperty pathDetObjImgAcqDistDirProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqDistDir", "");
	// final private StringProperty pathDetObjImgAcqPrefixProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqPrefix", "");
	final private DoubleProperty pathDetObjImgAcqMPPProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqMPP", 0.124);
	final private BooleanProperty pathDetObjImgAcqDontRescalingProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqDontRescaling", true);
	final private IntegerProperty pathDetObjImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqSamplingSize", 36);
	final private IntegerProperty pathDetObjImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqSamplingNum", -1);
	final private StringProperty pathDetObjImgAcqSamplingFmtProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqSamplingFmt", "");
	
	
	/**
	 * Constructor.
	 */
	public PathDetectionObjectImageAcquisition() {
		// final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();		
		// final double imagePixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		

		
		
		params = new ParameterList()
			.addStringParameter("distFolder", "Distination Folder", pathDetObjImgAcqDistDirProp.get(), "Distination Folder")
			// .addStringParameter("prefix", "Prefix", "", "Prefix")
			.addEmptyParameter("")
			.addEmptyParameter("Reampling using...")
			.addDoubleParameter("MPP", "pixel size", pathDetObjImgAcqMPPProp.get(), GeneralTools.micrometerSymbol(), "Pixel Size")
			.addEmptyParameter("...or...")
			.addBooleanParameter("dontResampling", "Do not rescaling image (default: yes)", pathDetObjImgAcqDontRescalingProp.get(), "Do not rescaling image (default: yes)")
			.addEmptyParameter("")
			.addIntParameter("samplingSize", "Sampling Size", pathDetObjImgAcqSamplingSizeProp.get(), "pixel(s)", "Sampling Size")
			.addIntParameter("samplingNum", "Maximal Sampling Number (-1 means all)", pathDetObjImgAcqSamplingNumProp.get(), "objects(s)", "Maximal Sampling Number")
			.addStringParameter("format", "Image File Format (e.g., png, tiff, etc.) ", pathDetObjImgAcqSamplingFmtProp.get(), "Image File Format");
	}
	
	class DetectedObjectImageSampling implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			pathDetObjImgAcqDistDirProp.set(params.getStringParameterValue("distFolder"));
			// pathDetObjImgAcqPrefixProp.set(params.getStringParameterValue("prefix"));
			pathDetObjImgAcqMPPProp.set(params.getDoubleParameterValue("MPP"));
			pathDetObjImgAcqDontRescalingProp.set(params.getBooleanParameterValue("dontResampling"));
			pathDetObjImgAcqSamplingSizeProp.set(params.getIntParameterValue("samplingSize"));
			pathDetObjImgAcqSamplingNumProp.set(params.getIntParameterValue("samplingNum"));
			pathDetObjImgAcqSamplingFmtProp.set(params.getStringParameterValue("format"));
					
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
					
			try {
				final List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(new ArrayList<>());
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation())
						selectedAnnotationPathObjectList.add(pathObject);
				}	
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
				final String serverPath = server.getPath();
				
				final double imageMPP = server.getPixelCalibration().getAveragedPixelSizeMicrons();
				
				final double scalingFactor = params.getDoubleParameterValue("MPP") / imageMPP;
				final int samplingFeatureSize = (int)(0.5 + scalingFactor * params.getIntParameterValue("samplingSize"));

				final AtomicBoolean success = new AtomicBoolean(true);
				
				selectedAnnotationPathObjectList.parallelStream().forEach(sltdObj -> {
					final List<PathObject> pathObjects = Collections.synchronizedList(new ArrayList<PathObject>(sltdObj.getChildObjects()));
					Collections.shuffle(pathObjects);
					
					final int samplingNum = params.getIntParameterValue("samplingNum") == -1 || params.getIntParameterValue("samplingNum") > pathObjects.size()? pathObjects.size(): params.getIntParameterValue("samplingNum");
					final List<PathObject> samplingPathObjects = Collections.synchronizedList(pathObjects.subList(0, samplingNum));
					
				    IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> { 
					// for(int i = 0; i < samplingPathObjects.size(); i ++) {
						final PathObject objObject = pathObjects.get(i);
						final ROI objRoi = objObject.getROI();
						
					    final int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double)samplingFeatureSize / 2.0));
					    final int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double)samplingFeatureSize / 2.0));
					    final RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, samplingFeatureSize, samplingFeatureSize);
						
						try {
							final BufferedImage img = (BufferedImage)server.readBufferedImage(objRegion);
							
							 final BufferedImage scaledImg = 
									params.getBooleanParameterValue("dontResampling")?
									img:
									Scalr.resize(img, params.getIntParameterValue("samplingSize"));
									
							final Path locationPath = Paths.get(params.getStringParameterValue("distFolder"));
							if(!Files.exists(locationPath)) new File(locationPath.toString()).mkdirs();
							
							// final Path labelPath = Paths.get(params.getStringParameterValue("distFolder"), params.getStringParameterValue("prefix")+sltdObj.getID().toString());
							final Path labelPath = Paths.get(params.getStringParameterValue("distFolder"), sltdObj.getID().toString());
							if(!Files.exists(labelPath)) new File(labelPath.toString()).mkdirs();

							final String format = params.getStringParameterValue("format").strip();
							final String fileExt = format.charAt(0) == '.'? format.substring(1): format;	
							
							final Path imageFilePath = Paths.get(labelPath.toString(), objObject.getID().toString()+"."+fileExt);
							final File imageFile = new File(imageFilePath.toString());	
							
					        ImageIO.write(scaledImg, fileExt, imageFile);
						} 
						catch (IOException e) {
							success.set(false);
							// payloadSuccess.set(false);
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					});
					// }
				
				});
				
				if(!success.get()) throw new Exception("Something went wron");
				// success.set(true);
		    }
		    catch (Exception e) {
				// TODO Auto-generated catch block
		    	Dialogs.showErrorMessage("Error", e.getMessage());
				e.printStackTrace();
			}
		    finally {
			    System.gc();  
		    }
			
			
			
			
			
			
			
			
			
			
			return hierarchy.getAnnotationObjects();
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
		tasks.add(DetectionPluginTools.createRunnableTask(new DetectedObjectImageSampling(), getParameterList(imageData), imageData, parentObject));
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
//		List<Class<? extends PathObject>> list = new ArrayList<>();
//		list.add(TMACoreObject.class);
////		list.add(PathAnnotationObject.class);
//		list.add(PathRootObject.class);
//		return list;
		return Arrays.asList(
				PathAnnotationObject.class,
				TMACoreObject.class
				);		
	}

}
