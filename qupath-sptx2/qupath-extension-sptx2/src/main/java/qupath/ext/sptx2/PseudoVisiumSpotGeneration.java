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
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;

import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
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
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class PseudoVisiumSpotGeneration extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(PseudoVisiumSpotGeneration.class);
	
	private ParameterList params;
	final private StringProperty pseVisSptVendorProp = PathPrefs.createPersistentPreference("pseVisSptVendor", "xenium"); 
	final private List<String> vendorlList = Arrays.asList("xenium", "cosmx");
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public PseudoVisiumSpotGeneration() {
		params = new ParameterList()
			// .addTitleParameter(lastResults)
			.addTitleParameter("10x Pseudo Visium Spot Generator")
			.addDoubleParameter("spotDiameter", "Spot Size", 55, GeneralTools.micrometerSymbol(), "Spot Diameter")			
			.addDoubleParameter("minSpotDist", "Minimal Spot Distance", 100, GeneralTools.micrometerSymbol(), "Minimal Spot Distance")			
			// .addBooleanParameter("consolToAnnot", "Consolidate transcript data to Visium-style spots? (default: false)", false, "Consolidate Transcript Data to Annotations? (default: false)")
			.addBooleanParameter("rectGridArrangement", "Rectangular grid arrangement? (default: false)", false, "Hexagonal arrangement? (default: true)")		
			.addBooleanParameter("rectShapeSpot", "Rectangular shape spot? (default: false)", false, "Round shape spot? (default: true)")	
			.addChoiceParameter("vendor", "Vendor", pseVisSptVendorProp.get(), vendorlList, "Choose the vendor that should be used for object classification")
			.addStringParameter("prefix", "Spot ID Prefix", "pseudo-spot", "Spot ID Prefix")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			pseVisSptVendorProp.set((String)params.getChoiceParameterValue("vendor"));
			
			final ImageServer<BufferedImage> server = imageData.getServer();				
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final double pixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
			final int imageHeight = server.getHeight();
			final int imageWidth = server.getWidth();
			
			final double spotDiameterMicrons = params.getDoubleParameterValue("spotDiameter");
			final double minSpotDistMicrons = params.getDoubleParameterValue("minSpotDist");
			final double spotDiameterPx = spotDiameterMicrons/pixelSizeMicrons;
			final double minSpotDistPx = minSpotDistMicrons/pixelSizeMicrons;
			
			final ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>();
			
			try {
				final List<PathObject> selectedAnnotationPathObjectList = new ArrayList<>();
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation())
						selectedAnnotationPathObjectList.add(pathObject);
				}	
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				final int maskWidth = (int)Math.round(imageData.getServer().getWidth());
				final int maskHeight = (int)Math.round(imageData.getServer().getHeight());
				
				final BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_BYTE_GRAY);
				final List<PathObject> pathObjectList = new ArrayList<PathObject>();						
				
				final Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
				pathObjectG2D.setBackground(Color.BLACK);
				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				
				
				for(PathObject p: selectedAnnotationPathObjectList) {
					
					pathObjectList.add(p);
				    
			
			    
				    final ROI roi = p.getROI();
					final Shape shape = roi.getShape();
					
					pathObjectG2D.setColor(Color.WHITE);
					pathObjectG2D.fill(shape);
					
			
					
				}	
				
				pathObjectG2D.dispose();	

				final int halfSpotDiameterPx = (int)Math.ceil(spotDiameterPx/2.0);
				final int y_step = params.getBooleanParameterValue("rectShapeSpot") || params.getBooleanParameterValue("rectGridArrangement")? (int)minSpotDistPx: (int)Math.round(0.5*Math.sqrt(3)*minSpotDistPx);
				
				int row_count = 0;
				int col_count = 0;
				boolean even_row_flag = false;
				
				for(int y = halfSpotDiameterPx; y < imageHeight-halfSpotDiameterPx; y += y_step) {
					int even_row_shift = even_row_flag && !params.getBooleanParameterValue("rectGridArrangement")? halfSpotDiameterPx: 0;
					
					for(int x = halfSpotDiameterPx+even_row_shift; x < imageWidth-halfSpotDiameterPx; x += minSpotDistPx) {
						if(pathObjectImageMask.getRGB(x, y) == Color.WHITE.getRGB()) {
							final ROI pathRoi = !params.getBooleanParameterValue("rectShapeSpot")? 
								ROIs.createEllipseROI(x, y, spotDiameterPx, spotDiameterPx, null):
								ROIs.createRectangleROI(x-halfSpotDiameterPx, y-halfSpotDiameterPx, spotDiameterPx, spotDiameterPx, null);
								
							final PathClass pathCls = PathClassFactory.getPathClass(params.getStringParameterValue("prefix")+"-"+Integer.toString(row_count)+"-"+Integer.toString(col_count));
							final PathAnnotationObject pathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
							resultPathObjectList.add(pathObj); 
						}

						col_count ++;
					}

					row_count ++;
					even_row_flag = !even_row_flag;
				}
				
//				if(params.getBooleanParameterValue("consolToAnnot")) {
//					// for(var d: hierarchy.getDetectionObjects()) {
//					hierarchy.getDetectionObjects().parallelStream().forEach(d -> {
//						if(d.getPathClass() != null && hierarchy.getRootObject() != d.getParent()) {
//							final MeasurementList parentPathObjMeasList = d.getParent().getMeasurementList();
//							final MeasurementList pathObjMeasList = d.getMeasurementList();
//							
//							final List<String> featNameList = pathObjMeasList.getMeasurementNames();
//							featNameList.parallelStream().forEach(n -> {
//							// for(var n: featNameList) {
//								final String prefix = params.getChoiceParameterValue("vendor")+":cell_transcript:";
//								if(n.startsWith(prefix)) {
//									final String gene = n.substring(prefix.length());
//									final String parentMeasName = params.getChoiceParameterValue("vendor")+":spot_transcript:"+gene;
//									final String measName = params.getChoiceParameterValue("vendor")+":cell_transcript:"+gene;
//									
//									final double oldVal = 
//											parentPathObjMeasList.containsKey(parentMeasName)? 
//				        					parentPathObjMeasList.get(parentMeasName): 
//				        					0.0;
//									
//									final double newVal = pathObjMeasList.get(measName);
//									
//									synchronized(parentPathObjMeasList) {
//										parentPathObjMeasList.put(parentMeasName, newVal+oldVal);  
//									}
//								}
//							// }
//							});
//							
//							synchronized(parentPathObjMeasList) {
//								parentPathObjMeasList.close();
//							}
//							synchronized(pathObjMeasList) {
//								pathObjMeasList.close();
//							}
//						}
//					// }
//					});
//				}
				
				
				
				
				resultPathObjectList.addAll(hierarchy.getRootObject().getChildObjects());
				
				hierarchy.getSelectionModel().setSelectedObject(null);
				// QuPathGUI.getInstance().getViewer().setSelectedObject(null);
			}
			catch(Exception e) {	
				Dialogs.showErrorMessage("Error", e.getMessage());
				
				lastResults =  "Something went wrong: "+e.getMessage();
				
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}				
			
			if (Thread.currentThread().isInterrupted()) {
				Dialogs.showErrorMessage("Warning", "Interrupted!");
				
				lastResults =  "Interrupted!";
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}
			
			
			
			return resultPathObjectList;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
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
	}


	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
//		List<Class<? extends PathObject>> list = new ArrayList<>();
//		list.add(TMACoreObject.class);
//		list.add(PathRootObject.class);
//		return list;
		return Arrays.asList(
				PathAnnotationObject.class,
				TMACoreObject.class
				);		
	}

}
