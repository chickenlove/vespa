// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <memory>

namespace document { class DocumentType; }
namespace vespa { namespace config { namespace search { namespace internal {
class InternalAttributesType;
class InternalIndexschemaType;
class InternalSummarymapType;
} } } }

namespace proton {

class IDocumentTypeInspector;
class IIndexschemaInspector;

/*
 * Class to build adjusted attributes config and summary map config
 * to eliminate need for reprocessing when system is online.
 */
class AttributeAspectDelayer
{
    using AttributesConfigBuilder = vespa::config::search::internal::InternalAttributesType;
    using AttributesConfig = const vespa::config::search::internal::InternalAttributesType;
    using DocumentType = document::DocumentType;
    using IndexschemaConfig = const vespa::config::search::internal::InternalIndexschemaType;
    using SummarymapConfigBuilder = vespa::config::search::internal::InternalSummarymapType;
    using SummarymapConfig = const vespa::config::search::internal::InternalSummarymapType;

    std::shared_ptr<AttributesConfigBuilder> _attributesConfig;
    std::shared_ptr<SummarymapConfigBuilder> _summarymapConfig;

public:
    AttributeAspectDelayer();
    ~AttributeAspectDelayer();

    /*
     * Setup to avoid reprocessing, used to create adjusted document db
     * config before applying new config when system is online.
     */
    void setup(const AttributesConfig &oldAttributesConfig,
               const SummarymapConfig &oldSummarymapConfig,
               const AttributesConfig &newAttributesConfig,
               const SummarymapConfig &newSummarymapConfig,
               const IIndexschemaInspector &oldIndexschemaInspector,
               const IDocumentTypeInspector &inspector);

    std::shared_ptr<AttributesConfig> getAttributesConfig() const;
    std::shared_ptr<SummarymapConfig> getSummarymapConfig() const;
};

} // namespace proton
