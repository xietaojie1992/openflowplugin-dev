package org.opendaylight.openflowplugin.openflow.md.core.sal.convertor;

import org.opendaylight.openflowplugin.extension.api.ConvertorMessageToOFJava;
import org.opendaylight.openflowplugin.extension.api.TypeVersionKey;
import org.opendaylight.openflowplugin.extension.api.core.extension.ExtensionConverterProvider;
import org.opendaylight.openflowplugin.extension.api.exception.ConversionException;
import org.opendaylight.openflowplugin.openflow.md.core.session.OFSessionUtil;
import org.opendaylight.yang.gen.v1.urn.opendaylight.experimenter.message.service.rev151020.SendExperimenterInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.ExperimenterInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.experimenter.core.ExperimenterDataOfChoice;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.experimenter.types.rev151020.experimenter.core.message
        .ExperimenterMessageOfChoice;
import org.slf4j.Logger;

/**
 * @author xietaojie1992
 */
public final class ExperimenterConvertor {
    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(ExperimenterConvertor.class);

    private ExperimenterConvertor() {
    }

    // Get all the data for the experimenter from the Yang/SAL-Layer,vmmvn
    public static ExperimenterInputBuilder toExperimenterInput(SendExperimenterInput input, short version) {
        LOG.info("toExperimenterInput: {}", input);

        ExtensionConverterProvider extensionConverterProvider = OFSessionUtil.getExtensionConvertorProvider();
        final TypeVersionKey key = new TypeVersionKey(input.getExperimenterMessageOfChoice().getImplementedInterface(), version);
        final ConvertorMessageToOFJava<ExperimenterMessageOfChoice, ExperimenterDataOfChoice> messageConverter = extensionConverterProvider
                .getMessageConverter(key);
        if (messageConverter == null) {
            LOG.error("Converter Not Found Exception");
        }

        ExperimenterInputBuilder experimenterInputBld = null;

        try {
            experimenterInputBld = new ExperimenterInputBuilder().setExperimenter(messageConverter.getExperimenterId()).setExpType(
                    messageConverter.getType()).setExperimenterDataOfChoice(
                    messageConverter.convert(input.getExperimenterMessageOfChoice())).setVersion(version);
            LOG.info("from convertor, ExperimenterInputBuilder = {}", experimenterInputBld.build());
        } catch (ConversionException e) {
            e.printStackTrace();
        }

        return experimenterInputBld;
    }
}