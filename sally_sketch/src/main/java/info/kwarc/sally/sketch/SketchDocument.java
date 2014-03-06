package info.kwarc.sally.sketch;

import info.kwarc.sally.core.comm.SallyMenuItem;
import info.kwarc.sally.core.composition.SallyContext;
import info.kwarc.sally.core.composition.SallyInteraction;
import info.kwarc.sally.core.composition.SallyInteractionResultAcceptor;
import info.kwarc.sally.core.composition.SallyService;
import info.kwarc.sally.core.interaction.CallbackManager;
import info.kwarc.sally.core.interaction.IMessageCallback;
import info.kwarc.sally.core.net.INetworkSender;
import info.kwarc.sally.core.rdf.IM;
import info.kwarc.sally.core.rdf.RDFStore;
import info.kwarc.sally.core.theo.Coordinates;
import info.kwarc.sally.core.theo.IPositionProvider;
import info.kwarc.sally.core.workflow.ISallyWorkflowManager;
import info.kwarc.sally.core.workflow.ProcessInstance;
import info.kwarc.sally.sketch.ontology.Sketch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sally.HTMLSelectPart;
import sally.MMTUri;
import sally.ScreenCoordinates;
import sally.SketchASM;
import sally.SketchAtomic;
import sally.SketchSelect;
import sally.SketchSelectPart;
import sally.SoftwareObject;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.protobuf.AbstractMessage;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.vocabulary.RDF;

public class SketchDocument {
	String filePath;
	SketchASM data;
	INetworkSender sender;
	HashMap<String, String> urimap;
	IPositionProvider provider;
	Logger log;
	RDFStore rdfStore;
	CallbackManager callbacks;
	ISallyWorkflowManager wm;

	@Inject
	public SketchDocument(@Assisted String filePath, @Assisted SketchASM data, @Assisted INetworkSender sender,  IPositionProvider provider, ISallyWorkflowManager wm, RDFStore rdfStore, CallbackManager callbacks) {
		this.callbacks = callbacks;
		this.filePath = filePath;
		this.data = data;
		this.sender = sender;
		this.provider = provider;
		this.rdfStore = rdfStore;
		this.wm = wm;
		log = LoggerFactory.getLogger(getClass());
		urimap = new HashMap<String, String>();
		init();
	}

	void init() {
		for (SketchAtomic sa : data.getPartsList()) {
			urimap.put(sa.getId(), sa.getMmturi().getUri());
		}
		rdfStore.addModel(filePath, createModel());
	}

	private Model createModel() {
		Model model = ModelFactory.createDefaultModel();
		model.setNsPrefix("rdf", RDF.getURI());
		for (String  key : urimap.keySet()) {
			Resource comp = model.createResource();
			model.add(comp, IM.partOfFile, model.createLiteral(filePath));
			model.add(comp, IM.ontologyURI, model.createLiteral(urimap.get(key)));
			model.add(comp, Sketch.hasSketchID, model.createTypedLiteral(key));
		}
		return model;
	}

	public void selectObject(String id) {
		SketchSelectPart selCmd = SketchSelectPart.newBuilder().setId(id).setFileName(filePath).build();
		sender.sendMessage("/sketch/sketchSelectPart", selCmd);
	}

	@SallyService(channel="navigateTo")
	public void navigateTo(final SoftwareObject so, SallyInteractionResultAcceptor acceptor, SallyContext context) {
		if (!filePath.equals(so.getFileName()))
			return;
		acceptor.acceptResult(new Runnable() {

			@Override
			public void run() {
				selectObject(so.getUri().substring(7));
			}
		});
	}

	@SallyService	
	public void sketchClickInteraction(MMTUri mmtURI, SallyInteractionResultAcceptor acceptor, SallyContext context) {
		final Long parentProcessInstanceID = context.getContextVar("processInstanceId", Long.class);
		String origFile = context.getContextVar("origFile", String.class);
		if (filePath.equals(origFile))
			return;

		final List<String> refs = new ArrayList<String>();
		for (String key : urimap.keySet()) {
			if (urimap.get(key).equals(mmtURI.getUri())) {
				refs.add(key);
			}
		}
		if (refs.size() == 0)
			return;
		if (refs.size() > 1) {
			acceptor.acceptResult(new SallyMenuItem("Go to", "In "+filePath+" ("+refs.size()+")", "Show references in figure ") {
				@Override
				public void run() {
					Long callbackid = callbacks.registerCallback(new IMessageCallback() {
						@Override
						public void onMessage(AbstractMessage m) {
							selectObject(((HTMLSelectPart)m).getId());
						}
					});
					HashMap<String, Object>  input = new  HashMap<String, Object>();

					input.put("ObjectIDs", refs);
					input.put("CallbackID", Long.toString(callbackid));
					ProcessInstance pi =wm.prepareProcess(parentProcessInstanceID, "Sally.sketch_navigation", input);
					pi.setProcessVarialbe("ServiceURL", "http://localhost:8181/sally/html/navigate?id="+pi.getId());
					wm.startProcess(pi);					
				}
			});
		} else {
			acceptor.acceptResult(new SallyMenuItem("Go to", "In "+filePath, "Show reference in figure ") {
				@Override
				public void run() {
					selectObject(refs.get(0));
				}
			});
		}
	}

	@SallyService
	public void sketchClickInteraction(SketchSelect click, SallyInteractionResultAcceptor acceptor, SallyContext context) {
		if (!click.getFileName().equals(filePath)) {
			return;
		}
		final SallyInteraction interaction = context.getCurrentInteraction();
		context.setContextVar("origFile", filePath);

		ScreenCoordinates coords = click.getPosition();
		provider.setRecommendedCoordinates(new Coordinates(coords.getX(), coords.getY()));

		List<SallyMenuItem> items = new ArrayList<SallyMenuItem>();

		SoftwareObject obj = SoftwareObject.newBuilder().setFileName(filePath).setUri("htmlid#"+click.getId()).build();
		items.addAll(interaction.getPossibleInteractions(obj, SallyMenuItem.class));

		MMTUri mmtURI = MMTUri.newBuilder().setUri(getSemantics(click.getId())).build();
		if (mmtURI != null)
			items.addAll(interaction.getPossibleInteractions(mmtURI, SallyMenuItem.class));

		for (SallyMenuItem r : items) {
			acceptor.acceptResult(r);
		}
	}

	public String getSemantics(String id) {
		return urimap.get(id);
	}

}