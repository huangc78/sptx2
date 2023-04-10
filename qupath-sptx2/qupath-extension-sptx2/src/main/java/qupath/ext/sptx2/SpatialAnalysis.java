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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

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
import javafx.beans.property.IntegerProperty;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;
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

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class SpatialAnalysis extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(XeniumAnnotation.class);
	
	final private StringProperty sptAnalTgtClsProp = PathPrefs.createPersistentPreference("sptAnalTgtCls", ""); 
	final private StringProperty sptAnalOptClsProp = PathPrefs.createPersistentPreference("sptAnalOptCls", ""); 
	final private StringProperty sptAnalVendorProp = PathPrefs.createPersistentPreference("sptAnalVendor", "xenium"); 
	final private StringProperty sptAnalIdProp = PathPrefs.createPersistentPreference("sptAnalId", "default"); 
	final private IntegerProperty sptAnalLayersProp = PathPrefs.createPersistentPreference("sptAnalLayer", 10); 
	final private List<String> vendorlList = Arrays.asList("xenium", "cosmx");
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public SpatialAnalysis() {
		final PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
		
        // Synchronizing ArrayList in Java  
        List<String> availPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        List<String> selectedPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        
		hierarchy.getDetectionObjects().parallelStream().forEach(d -> {
			if(d.getPathClass() != null) {
				synchronized (availPathClassList) {  
					if(!availPathClassList.contains(d.getPathClass().getName())) {
						availPathClassList.add(d.getPathClass().getName());
					}
				}
			}
		});
		
		hierarchy.getSelectionModel().getSelectedObjects().parallelStream().forEach(d -> {
			if(d.getPathClass() != null) {
				synchronized (availPathClassList) {  
					if(availPathClassList.contains(d.getPathClass().getName())) {
						availPathClassList.remove(d.getPathClass().getName());
					}
				}
				
				synchronized (selectedPathClassList) {  
					if(!selectedPathClassList.contains(d.getPathClass().getName())) {
						selectedPathClassList.add(d.getPathClass().getName());
					}
				}
			}
		});
		
		final String posClsList = String.join(",", selectedPathClassList);
		final String negClsList = String.join(",", availPathClassList);
		
		params = new ParameterList()
			.addTitleParameter("Spatial Analysis")
			.addStringParameter("tgtCls", "Targeting Class(es)", posClsList, "Targeting Class(es)")
			.addStringParameter("optCls", "Opponent Class(es)", negClsList, "Opponent Class(es)")
			.addChoiceParameter("vendor", "Vendor", sptAnalVendorProp.get(), vendorlList, "Choose the vendor that should be used for object classification")
			.addStringParameter("id", "Layer ID", sptAnalIdProp.get(), "Layer ID")
			.addIntParameter("layers", "Maximal layers of detection", sptAnalLayersProp.get(), null, "Maximal layers of detection")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			sptAnalTgtClsProp.set(params.getStringParameterValue("tgtCls"));
			sptAnalOptClsProp.set(params.getStringParameterValue("optCls"));
			sptAnalVendorProp.set((String)params.getChoiceParameterValue("vendor"));
			sptAnalIdProp.set(params.getStringParameterValue("id"));
			sptAnalLayersProp.set(params.getIntParameterValue("layers"));
			
			// final String prefix = params.getStringParameterValue("prefix").isBlank()? "layer": params.getStringParameterValue("prefix");
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			final PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
					
			try {
	            /*
	             * Generate cell masks with their labels
	             */
				
				final List<PathObject> selectedAnnotationPathObjectList = new ArrayList<>();
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation() && pathObject.hasChildren())
						selectedAnnotationPathObjectList.add(pathObject);
				}	
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");

				final List<String> tgtClsLst = Arrays.stream(params.getStringParameterValue("tgtCls").split(",")).map(s -> s.replaceAll("\\s", "")).collect(Collectors.toList());
				List<String> optClsLst = Arrays.stream(params.getStringParameterValue("optCls").split(",")).map(s -> s.replaceAll("\\s", "")).collect(Collectors.toList());
				
				for(int l = 0; l < params.getIntParameterValue("layers"); l ++) {
					final int layer = l;
					selectedAnnotationPathObjectList.parallelStream().forEach(p -> {
					// for(PathObject p: selectedAnnotationPathObjectList) {
						for(PathObject c: p.getChildObjects()) {
							if(c.getMeasurementList().containsKey((String)params.getChoiceParameterValue("vendor")+":cell:layer:"+params.getStringParameterValue("id"))) continue;
							
							final PathClass cPthCls = c.getPathClass();
							
							if(cPthCls == null) continue;
							
							final String cCls = cPthCls.toString().replaceAll("\\s", "");;
							
							if(tgtClsLst.stream().anyMatch(cCls::equals)) {
								final List<PathObject> connectedObj = connections.getConnections(c);
								
								for(PathObject d: connectedObj) {
									if(layer == 0) {
										final PathClass dPthCls = d.getPathClass();
										if(dPthCls == null) continue;
										
										final String dCls = dPthCls.toString().replaceAll("\\s", "");;
										if(optClsLst.stream().anyMatch(dCls::equals)) {
											final MeasurementList tgtObjMeasList = c.getMeasurementList();
											tgtObjMeasList.put((String)params.getChoiceParameterValue("vendor")+":cell:layer:"+params.getStringParameterValue("id"), layer);
											tgtObjMeasList.close();
											break;
										}
									}
									else {
										final MeasurementList optObjMeasList = d.getMeasurementList();
										Double v = optObjMeasList.get((String)params.getChoiceParameterValue("vendor")+":cell:layer:"+params.getStringParameterValue("id"));
										
										if((!v.isNaN()) && (v.intValue() == layer-1)) {
											final MeasurementList tgtObjMeasList = c.getMeasurementList();
											tgtObjMeasList.put((String)params.getChoiceParameterValue("vendor")+":cell:layer:"+params.getStringParameterValue("id"), layer);
											tgtObjMeasList.close();
											break;
										}	
									}
								}
							}
						}
					});
					// }
				}
				
		        hierarchy.getSelectionModel().setSelectedObject(null);
				
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
			
			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
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
